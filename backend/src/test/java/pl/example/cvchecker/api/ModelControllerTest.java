package pl.example.cvchecker.api;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import pl.example.cvchecker.llm.ModelContextService;
import pl.example.cvchecker.llm.ModelInfo;
import pl.example.cvchecker.llm.ModelState;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Model context endpoints (docs/API.md §3). The LM Studio calls live behind {@link ModelContextService},
 * so only the boundary contract — shape of GET /api/llm/models, PUT validation, the deprecated load
 * blank-check — is exercised offline here.
 */
@WebMvcTest(ModelController.class)
class ModelControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ModelContextService models;

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

    /** The deprecated flow's blank-check is kept: the old frontend posts to the old path (docs/API.md §7). */
    @Test
    void deprecatedLoadModelBlankReturns400() throws Exception {
        mvc.perform(post("/api/llm/models/load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MODEL_BLANK"));
    }
}