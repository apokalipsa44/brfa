package pl.example.cvchecker.api;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import pl.example.cvchecker.analysis.Analysis;
import pl.example.cvchecker.analysis.AnalysisRegistry;
import pl.example.cvchecker.analysis.AnalysisStage;
import pl.example.cvchecker.llm.ModelContextService;
import pl.example.cvchecker.llm.ModelInfo;
import pl.example.cvchecker.llm.ModelState;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Model context endpoints (docs/API.md §3). The LM Studio calls live behind {@link ModelContextService},
 * so only the boundary contract — shape of GET /api/llm/models, PUT validation — is exercised offline here.
 */
@WebMvcTest(ModelController.class)
class ModelControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ModelContextService models;

    @MockitoBean
    AnalysisRegistry analyses;

    @Test
    void modelStateReturnsCandidatesActiveAndReady() throws Exception {
        when(models.state()).thenReturn(new ModelState(
                List.of(new ModelInfo("qwen/iq3_s.gguf", true), new ModelInfo("gemma-4-e4b", false)),
                "qwen/iq3_s.gguf", true, null));

        mvc.perform(get("/api/llm/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models[0].id").value("qwen/iq3_s.gguf"))
                .andExpect(jsonPath("$.models[0].loaded").value(true))
                .andExpect(jsonPath("$.models[1].id").value("gemma-4-e4b"))
                .andExpect(jsonPath("$.models[1].loaded").value(false))
                .andExpect(jsonPath("$.activeModelId").value("qwen/iq3_s.gguf"))
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.reason").doesNotExist());
    }

    @Test
    void readyFalseCarriesReason() throws Exception {
        when(models.state()).thenReturn(new ModelState(List.of(), null, false, "No model selected"));

        mvc.perform(get("/api/llm/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.reason").value("No model selected"));
    }

    @Test
    void blankModelReturns400() throws Exception {
        mvc.perform(put("/api/llm/active-model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MODEL_BLANK"));
    }

    @Test
    void missingModelReturns400() throws Exception {
        mvc.perform(put("/api/llm/active-model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MODEL_BLANK"));
    }

    @Test
    void selectValidModelReturnsState() throws Exception {
        when(models.select("qwen/iq3_s.gguf")).thenReturn(
                new ModelState(List.of(new ModelInfo("qwen/iq3_s.gguf", true)), "qwen/iq3_s.gguf", true, null));

        mvc.perform(put("/api/llm/active-model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":\"qwen/iq3_s.gguf\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeModelId").value("qwen/iq3_s.gguf"))
                .andExpect(jsonPath("$.ready").value(true));

        verify(models).select("qwen/iq3_s.gguf");
    }

    /** A missing body is rejected before any LM Studio call (docs/API.md §3). */
    @Test
    void missingBodyReturns400() throws Exception {
        mvc.perform(put("/api/llm/active-model").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").exists());
    }

    /** A switch would unload the model a running analysis uses, so it is refused (docs/API.md §6.3). */
    @Test
    void switchWhileAnalysisRunningReturns409() throws Exception {
        Analysis running = mock(Analysis.class);
        when(running.stage()).thenReturn(AnalysisStage.EVALUATING);
        when(analyses.findAll()).thenReturn(List.of(running));

        mvc.perform(put("/api/llm/active-model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":\"qwen/iq3_s.gguf\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MODEL_SWITCH_BUSY"));

        verify(models, never()).select(anyString());
    }

    /** An analysis paused for review holds no model work, so switching is allowed (docs/API.md §6.3). */
    @Test
    void switchWhileAnalysisAwaitingReviewIsAllowed() throws Exception {
        Analysis awaiting = mock(Analysis.class);
        when(awaiting.stage()).thenReturn(AnalysisStage.AWAITING_REVIEW);
        when(analyses.findAll()).thenReturn(List.of(awaiting));
        when(models.select("qwen/iq3_s.gguf")).thenReturn(
                new ModelState(List.of(new ModelInfo("qwen/iq3_s.gguf", true)), "qwen/iq3_s.gguf", true, null));

        mvc.perform(put("/api/llm/active-model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":\"qwen/iq3_s.gguf\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeModelId").value("qwen/iq3_s.gguf"));
    }
}