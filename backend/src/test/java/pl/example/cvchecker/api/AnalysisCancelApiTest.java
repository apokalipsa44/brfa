package pl.example.cvchecker.api;

import java.time.LocalDate;
import java.util.List;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import pl.example.cvchecker.analysis.AnalysisStage;
import pl.example.cvchecker.llm.LmStudioClient;
import pl.example.cvchecker.llm.LmStudioModelList;
import pl.example.cvchecker.llm.ModelInfo;
import pl.example.cvchecker.pdf.TestPdfs;
import pl.example.cvchecker.storage.CvContextStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cancellation over REST against the simulated pipeline (docs/API.md §1.1). The one-thread executor
 * is deliberate: it proves that cancelling a running analysis frees the slot for the queued one, and
 * that retry resumes a cancelled analysis.
 */
@SpringBootTest(properties = {
        "cvchecker.pipeline.mode=simulated",
        "cvchecker.pipeline.stage-delay=1500ms",
        "cvchecker.persistence=memory"
})
@AutoConfigureMockMvc
class AnalysisCancelApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    CvContextStore cvStore;

    @MockitoBean
    LmStudioClient lmClient;

    @Test
    void cancellingARunningAnalysisFreesTheSlotForTheNextQueuedOne() throws Exception {
        makeModelReady();
        cvStore.place("cv.pdf", TestPdfs.pdf("Jan Kowalski", "Java, Spring Boot"), "Jan Kowalski, Java");

        String first = createAnalysis();
        String second = createAnalysis();

        // Wait until the first is actually evaluating, then cancel it.
        awaitStage(first, AnalysisStage.EVALUATING);
        mvc.perform(post("/api/analyses/" + first + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("CANCELLED"))
                .andExpect(jsonPath("$.interruptedStage").value("EVALUATING"));

        // The second analysis must now reach review within the time the freed slot allows.
        awaitStage(second, AnalysisStage.AWAITING_REVIEW);

        mvc.perform(get("/api/analyses/" + first))
                .andExpect(jsonPath("$.stage").value("CANCELLED"));
    }

    @Test
    void retryResumesACancelledAnalysis() throws Exception {
        makeModelReady();
        cvStore.place("cv.pdf", TestPdfs.pdf("Jan Kowalski", "Java, Spring Boot"), "Jan Kowalski, Java");

        String id = createAnalysis();
        awaitStage(id, AnalysisStage.EVALUATING);

        mvc.perform(post("/api/analyses/" + id + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("CANCELLED"));

        mvc.perform(post("/api/analyses/" + id + "/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.stage").value("EVALUATING"))
                .andExpect(jsonPath("$.interruptedStage").doesNotExist());

        awaitStage(id, AnalysisStage.AWAITING_REVIEW);
    }

    /** Retrying keeps user-set values: the applied date and the user title (docs/API.md §4). */
    @Test
    void retryKeepsTheAppliedDateAndUserTitle() throws Exception {
        makeModelReady();
        cvStore.place("cv.pdf", TestPdfs.pdf("Jan Kowalski", "Java, Spring Boot"), "Jan Kowalski, Java");

        String id = createAnalysis();
        awaitStage(id, AnalysisStage.EVALUATING);
        mvc.perform(post("/api/analyses/" + id + "/cancel")).andExpect(status().isOk());

        mvc.perform(patch("/api/analyses/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Interview at Globex\",\"applied\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedAt").value(LocalDate.now().toString()));

        mvc.perform(post("/api/analyses/" + id + "/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.title").value("Interview at Globex"))
                .andExpect(jsonPath("$.appliedAt").value(LocalDate.now().toString()));

        awaitStage(id, AnalysisStage.AWAITING_REVIEW);
        mvc.perform(get("/api/analyses/" + id))
                .andExpect(jsonPath("$.title").value("Interview at Globex"))
                .andExpect(jsonPath("$.appliedAt").value(LocalDate.now().toString()));
    }

    @Test
    void cancelAtTheReviewGateIsRejected() throws Exception {
        makeModelReady();
        cvStore.place("cv.pdf", TestPdfs.pdf("Jan Kowalski", "Java, Spring Boot"), "Jan Kowalski, Java");

        String id = createAnalysis();
        awaitStage(id, AnalysisStage.AWAITING_REVIEW);

        mvc.perform(post("/api/analyses/" + id + "/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_CANCELLABLE"));
    }

    private void makeModelReady() {
        when(lmClient.listModels())
                .thenReturn(new LmStudioModelList(true, List.of(new ModelInfo("qwen/iq3_s.gguf", true))));
        when(lmClient.probe("qwen/iq3_s.gguf")).thenReturn(true);
    }

    private String createAnalysis() throws Exception {
        mvc.perform(put("/api/llm/active-model").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":\"qwen/iq3_s.gguf\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true));

        MvcResult result = mvc.perform(post("/api/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobPosting\":\"Senior Java Developer wanted\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        return JsonPath.parse(result.getResponse().getContentAsString()).read("$.id");
    }

    private void awaitStage(String id, AnalysisStage expected) throws Exception {
        for (int i = 0; i < 100; i++) {
            MvcResult result = mvc.perform(get("/api/analyses/" + id)).andExpect(status().isOk()).andReturn();
            String stage = JsonPath.parse(result.getResponse().getContentAsString()).read("$.stage");
            if (expected.name().equals(stage)) {
                return;
            }
            Thread.sleep(100);
        }
        fail("Analysis " + id + " did not reach " + expected);
    }
}
