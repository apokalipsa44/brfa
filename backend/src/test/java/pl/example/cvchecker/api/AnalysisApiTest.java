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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import pl.example.cvchecker.analysis.AnalysisStage;
import pl.example.cvchecker.analysis.AnalysisUpdatedEvent;
import pl.example.cvchecker.llm.LmStudioClient;
import pl.example.cvchecker.llm.LmStudioModelList;
import pl.example.cvchecker.llm.ModelInfo;
import pl.example.cvchecker.pdf.TestPdfs;
import pl.example.cvchecker.storage.CvContextStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end analysis flow against the simulated pipeline: create → AWAITING_REVIEW → approve →
 * COMPLETED → download result → delete (docs/API.md §4). The LM Studio boundary is mocked only to
 * make the model context "ready" without a live server; the pipeline never calls it in this mode.
 */
@SpringBootTest(properties = {
        "cvchecker.pipeline.mode=simulated",
        "cvchecker.pipeline.stage-delay=100ms"
})
@AutoConfigureMockMvc
@RecordApplicationEvents
class AnalysisApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    CvContextStore cvStore;

    @Autowired
    ApplicationEvents events;

    @MockitoBean
    LmStudioClient lmClient;

    @Test
    void fullTwoStageFlowThroughRest() throws Exception {
        makeModelReady();
        cvStore.place("cv.pdf", TestPdfs.pdf("Jan Kowalski", "Java, Spring Boot"), "Jan Kowalski, Java");

        String id = createAnalysis();
        awaitStage(id, AnalysisStage.AWAITING_REVIEW);

        mvc.perform(get("/api/analyses/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Acme · Senior Java Developer"))
                .andExpect(jsonPath("$.score").value(58))
                .andExpect(jsonPath("$.suggestions[0].id").value("s1"));

        mvc.perform(post("/api/analyses/" + id + "/apply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedSuggestionIds\":[\"s1\"]}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.stage").value("REWRITING"));

        awaitStage(id, AnalysisStage.COMPLETED);
        mvc.perform(get("/api/analyses/" + id))
                .andExpect(jsonPath("$.appliedChanges[0].section").value("Skills"))
                .andExpect(jsonPath("$.resultUrl").value("/api/analyses/" + id + "/result"));

        mvc.perform(get("/api/analyses/" + id + "/result"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType())
                        .isEqualTo(MediaType.APPLICATION_PDF_VALUE));

        mvc.perform(delete("/api/analyses/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/analyses/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
    }

    /** PATCH persists title/appliedAt and broadcasts an analysis.updated event (docs/API.md §4). */
    @Test
    void patchUpdatesAndEmitsAnUpdateEvent() throws Exception {
        makeModelReady();
        cvStore.place("cv.pdf", TestPdfs.pdf("Jan Kowalski", "Java, Spring Boot"), "Jan Kowalski, Java");
        String id = createAnalysis();
        awaitStage(id, AnalysisStage.AWAITING_REVIEW);
        events.clear();

        mvc.perform(patch("/api/analyses/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Applied role\",\"applied\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Applied role"))
                .andExpect(jsonPath("$.appliedAt").value(LocalDate.now().toString()));

        boolean emitted = events.stream(AnalysisUpdatedEvent.class)
                .map(AnalysisUpdatedEvent::summary)
                .anyMatch(s -> id.equals(s.id()) && s.appliedAt() != null);
        assertThat(emitted).isTrue();

        mvc.perform(get("/api/analyses/" + id))
                .andExpect(jsonPath("$.title").value("Applied role"))
                .andExpect(jsonPath("$.appliedAt").value(LocalDate.now().toString()));
    }

    private void makeModelReady() {
        when(lmClient.listModels())
                .thenReturn(new LmStudioModelList(true, List.of(new ModelInfo("qwen/iq3_s.gguf", true))));
        when(lmClient.probe("qwen/iq3_s.gguf")).thenReturn(true);
    }

    private String createAnalysis() throws Exception {
        // Ensure the model is selected (and reported ready) before creating.
        mvc.perform(put("/api/llm/active-model").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":\"qwen/iq3_s.gguf\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true));

        MvcResult result = mvc.perform(post("/api/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobPosting\":\"Senior Java Developer wanted\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.stage").value("QUEUED"))
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