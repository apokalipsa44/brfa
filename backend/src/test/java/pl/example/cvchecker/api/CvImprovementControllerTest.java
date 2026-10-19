package pl.example.cvchecker.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import pl.example.cvchecker.cv.CvImprovementService;
import pl.example.cvchecker.cv.CvImprovementStage;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contract of the standalone CV-builder endpoints (docs/API.md §10). */
@WebMvcTest(CvImprovementController.class)
class CvImprovementControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CvImprovementService service;

    @TempDir
    Path temp;

    @Test
    void currentReturnsTheBuildState() throws Exception {
        when(service.state()).thenReturn(new CvImprovementState(CvImprovementStage.COMPLETED,
                List.of("Kubernetes", "GraphQL"), "qwen", Instant.parse("2026-10-18T10:00:00Z"),
                Instant.parse("2026-10-18T10:02:00Z"), null, null, "/api/cv/improvement/result?v=run1"));

        mvc.perform(get("/api/cv/improvement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("COMPLETED"))
                .andExpect(jsonPath("$.skills[0]").value("Kubernetes"))
                .andExpect(jsonPath("$.resultUrl").value("/api/cv/improvement/result?v=run1"));
    }

    @Test
    void startAcceptsAndReturnsRewritingState() throws Exception {
        when(service.start(anyList())).thenReturn(new CvImprovementState(CvImprovementStage.REWRITING,
                List.of("Kubernetes"), "qwen", Instant.parse("2026-10-18T10:00:00Z"), null, null, null, null));

        mvc.perform(post("/api/cv/improvement").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skills\":[\"Kubernetes\"]}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.stage").value("REWRITING"))
                .andExpect(jsonPath("$.skills[0]").value("Kubernetes"));
    }

    @Test
    void aConcurrentBuildReturns409() throws Exception {
        when(service.start(anyList())).thenThrow(
                new ApiException(409, "IMPROVEMENT_RUNNING", "Build in progress", "already running"));

        mvc.perform(post("/api/cv/improvement").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skills\":[\"Kubernetes\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPROVEMENT_RUNNING"));
    }

    @Test
    void emptySkillsReturn400() throws Exception {
        when(service.start(anyList())).thenThrow(
                new ApiException(400, "IMPROVEMENT_SKILLS_EMPTY", "No skills selected", "select one"));

        mvc.perform(post("/api/cv/improvement").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skills\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPROVEMENT_SKILLS_EMPTY"));
    }

    @Test
    void resultBeforeCompletionReturns409() throws Exception {
        when(service.resultPath()).thenThrow(
                new ApiException(409, "NOT_READY", "Build not completed", "still running"));

        mvc.perform(get("/api/cv/improvement/result"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_READY"));
    }

    @Test
    void resultDownloadsThePdf() throws Exception {
        Path pdf = temp.resolve("cv-improved.pdf");
        Files.write(pdf, new byte[] {0x25, 0x50, 0x44, 0x46}); // "%PDF"
        when(service.resultPath()).thenReturn(pdf);

        mvc.perform(get("/api/cv/improvement/result"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"cv-improved.pdf\""))
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(new byte[] {0x25, 0x50, 0x44, 0x46}));
    }
}
