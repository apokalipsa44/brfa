package pl.example.cvchecker.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import pl.example.cvchecker.analysis.Analysis;
import pl.example.cvchecker.analysis.AnalysisPipeline;
import pl.example.cvchecker.analysis.AnalysisRegistry;
import pl.example.cvchecker.analysis.AnalysisRuns;
import pl.example.cvchecker.analysis.AnalysisStage;
import pl.example.cvchecker.llm.Evaluation;
import pl.example.cvchecker.llm.ModelContextService;
import pl.example.cvchecker.llm.Priority;
import pl.example.cvchecker.llm.Suggestion;
import pl.example.cvchecker.pdf.TestPdfs;
import pl.example.cvchecker.storage.CvContext;
import pl.example.cvchecker.storage.CvContextStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract of the analyses endpoints (docs/API.md §4). The registry and pipeline live behind
 * interfaces/HashMap, so this slice exercises validation, stage gates and error codes offline.
 */
@WebMvcTest(AnalysisController.class)
class AnalysisControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AnalysisRegistry registry;

    @MockitoBean
    AnalysisPipeline pipeline;

    @MockitoBean
    AnalysisRuns runs;

    @MockitoBean
    CvContextStore cvStore;

    @MockitoBean
    ModelContextService models;

    private static CvContext cv() {
        return new CvContext("cv-1", "cv.pdf", "Jan Kowalski, Java", 10, Instant.now(), Path.of("/tmp/cv.pdf"));
    }

    private static Evaluation evaluation() {
        return new Evaluation("Acme", "Senior Java Developer", 58, List.of("Java"), List.of("Kubernetes"),
                List.of(new Suggestion("Skills", "Missing K8s", "Group container skills", "Docker",
                        "Docker, Kubernetes (basics)", Priority.HIGH)),
                "Solid match.", "Payment APIs in Java on a small team.");
    }

    /** An analysis stopped at the review gate, with one suggestion (id s1) and score 58. */
    private static Analysis awaitingReview() {
        Analysis analysis = new Analysis("a1", "cv.pdf", "Jan Kowalski, Java", "job", "qwen/iq3_s.gguf");
        analysis.setEvaluation(evaluation());
        analysis.transition(AnalysisStage.AWAITING_REVIEW, 40);
        return analysis;
    }

    @Test
    void validCreateReturns202Queued() throws Exception {
        when(cvStore.current()).thenReturn(Optional.of(cv()));
        when(models.isReady()).thenReturn(true);
        when(models.activeModelId()).thenReturn("qwen/iq3_s.gguf");

        mvc.perform(post("/api/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobPosting\":\"Senior Java Developer wanted\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.stage").value("QUEUED"))
                .andExpect(jsonPath("$.progress").value(0))
                .andExpect(jsonPath("$.score").doesNotExist());

        verify(registry).create(any(Analysis.class));
        verify(pipeline).start(any(Analysis.class));
    }

    @Test
    void blankPostingReturns422() throws Exception {
        mvc.perform(post("/api/analyses").contentType(MediaType.APPLICATION_JSON).content("{\"jobPosting\":\"  \"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("POSTING_REQUIRED"));
    }

    @Test
    void bothPostingAndUrlReturns422() throws Exception {
        mvc.perform(post("/api/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobPosting\":\"text\",\"jobPostingUrl\":\"https://example.com/job\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("POSTING_SOURCE_CONFLICT"));
    }

    @Test
    void urlCreateReturns202QueuedWithSourceUrl() throws Exception {
        when(cvStore.current()).thenReturn(Optional.of(cv()));
        when(models.isReady()).thenReturn(true);
        when(models.activeModelId()).thenReturn("qwen/iq3_s.gguf");

        mvc.perform(post("/api/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobPostingUrl\":\"https://example.com/job\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.stage").value("QUEUED"))
                .andExpect(jsonPath("$.attemptId").value(1));

        verify(pipeline).start(any(Analysis.class));
    }

    @Test
    void tooLongPostingReturns400() throws Exception {
        String longPosting = "x".repeat(20_001);
        mvc.perform(post("/api/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobPosting\":\"" + longPosting + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("JOB_DESCRIPTION_TOO_LONG"));
    }

    @Test
    void createWithoutCvReturns422() throws Exception {
        when(cvStore.current()).thenReturn(Optional.empty());

        mvc.perform(post("/api/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobPosting\":\"Senior Java Developer\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CV_MISSING"));
    }

    @Test
    void createWithModelNotReadyReturns422() throws Exception {
        when(cvStore.current()).thenReturn(Optional.of(cv()));
        when(models.isReady()).thenReturn(false);

        mvc.perform(post("/api/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobPosting\":\"Senior Java Developer\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MODEL_NOT_READY"));
    }

    @Test
    void listReturnsNewestFirst() throws Exception {
        Analysis older = new Analysis("a1", "cv.pdf", "text", "job", "qwen");
        Analysis newer = new Analysis("a2", "cv.pdf", "text", "job", "qwen");
        when(registry.findAll()).thenReturn(List.of(older, newer));

        mvc.perform(get("/api/analyses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("a2"))
                .andExpect(jsonPath("$[1].id").value("a1"));
    }

    @Test
    void detailReturnsEvaluationFields() throws Exception {
        when(registry.find("a1")).thenReturn(Optional.of(awaitingReview()));

        mvc.perform(get("/api/analyses/a1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Acme · Senior Java Developer"))
                .andExpect(jsonPath("$.stage").value("AWAITING_REVIEW"))
                .andExpect(jsonPath("$.score").value(58))
                .andExpect(jsonPath("$.shortDescription").value("Payment APIs in Java on a small team."))
                .andExpect(jsonPath("$.appliedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.modelId").value("qwen/iq3_s.gguf"))
                .andExpect(jsonPath("$.suggestions[0].id").value("s1"))
                .andExpect(jsonPath("$.suggestions[0].priority").value("HIGH"))
                .andExpect(jsonPath("$.suggestions[0].proposal").exists())
                .andExpect(jsonPath("$.postingText").value("job"))
                .andExpect(jsonPath("$.postingSource").value("PASTED"))
                .andExpect(jsonPath("$.postingTruncated").value(false))
                .andExpect(jsonPath("$.sourceUrl").doesNotExist())
                .andExpect(jsonPath("$.attemptId").value(1))
                .andExpect(jsonPath("$.resultUrl").doesNotExist());
    }

    @Test
    void detailUnknownReturns404() throws Exception {
        when(registry.find("nope")).thenReturn(Optional.empty());

        mvc.perform(get("/api/analyses/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
    }

    @Test
    void renameBlankReturns400() throws Exception {
        mvc.perform(patch("/api/analyses/a1").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TITLE_BLANK"));
    }

    @Test
    void renameReturnsUpdatedSummary() throws Exception {
        when(registry.find("a1")).thenReturn(Optional.of(awaitingReview()));

        mvc.perform(patch("/api/analyses/a1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Latest job — Acme\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Latest job — Acme"))
                .andExpect(jsonPath("$.stage").value("AWAITING_REVIEW"));
    }

    @Test
    void emptyPatchBodyReturns422() throws Exception {
        mvc.perform(patch("/api/analyses/a1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_UPDATES"));
    }

    @Test
    void patchWithNoRecognizedFieldsReturns422() throws Exception {
        mvc.perform(patch("/api/analyses/a1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unknown\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_UPDATES"));
    }

    @Test
    void appliedTrueSetsTodayWhenUnset() throws Exception {
        Analysis analysis = awaitingReview();
        when(registry.find("a1")).thenReturn(Optional.of(analysis));

        mvc.perform(patch("/api/analyses/a1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"applied\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedAt").value(LocalDate.now().toString()));

        assertThat(analysis.appliedAt()).isEqualTo(LocalDate.now());
    }

    @Test
    void appliedTrueKeepsAnExistingDate() throws Exception {
        Analysis analysis = awaitingReview();
        analysis.setApplied(LocalDate.now().minusDays(3));
        when(registry.find("a1")).thenReturn(Optional.of(analysis));

        mvc.perform(patch("/api/analyses/a1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"applied\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedAt").value(LocalDate.now().minusDays(3).toString()));
    }

    @Test
    void appliedFalseClearsTheDate() throws Exception {
        Analysis analysis = awaitingReview();
        analysis.setApplied(LocalDate.now().minusDays(1));
        when(registry.find("a1")).thenReturn(Optional.of(analysis));

        mvc.perform(patch("/api/analyses/a1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"applied\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedAt").value(org.hamcrest.Matchers.nullValue()));

        assertThat(analysis.appliedAt()).isNull();
    }

    @Test
    void explicitAppliedAtSetsTheDate() throws Exception {
        Analysis analysis = awaitingReview();
        when(registry.find("a1")).thenReturn(Optional.of(analysis));

        mvc.perform(patch("/api/analyses/a1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appliedAt\":\"2026-10-14\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedAt").value("2026-10-14"));
    }

    @Test
    void appliedAtAcceptsADateTime() throws Exception {
        Analysis analysis = awaitingReview();
        when(registry.find("a1")).thenReturn(Optional.of(analysis));

        mvc.perform(patch("/api/analyses/a1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appliedAt\":\"2026-10-14T09:30:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedAt").value("2026-10-14"));
    }

    @Test
    void malformedAppliedAtReturns422() throws Exception {
        when(registry.find("a1")).thenReturn(Optional.of(awaitingReview()));

        mvc.perform(patch("/api/analyses/a1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appliedAt\":\"not-a-date\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("APPLIED_AT_INVALID"));
    }

    @Test
    void futureAppliedAtReturns422() throws Exception {
        when(registry.find("a1")).thenReturn(Optional.of(awaitingReview()));
        String future = LocalDate.now().plusDays(1).toString();

        mvc.perform(patch("/api/analyses/a1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appliedAt\":\"" + future + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("APPLIED_AT_FUTURE"));
    }

    @Test
    void appliedAtWinsOverApplied() throws Exception {
        Analysis analysis = awaitingReview();
        when(registry.find("a1")).thenReturn(Optional.of(analysis));

        mvc.perform(patch("/api/analyses/a1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"applied\":false,\"appliedAt\":\"2026-10-10\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedAt").value("2026-10-10"));
    }

    @Test
    void combinedTitleAndAppliedUpdate() throws Exception {
        Analysis analysis = awaitingReview();
        when(registry.find("a1")).thenReturn(Optional.of(analysis));

        mvc.perform(patch("/api/analyses/a1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"My title\",\"applied\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("My title"))
                .andExpect(jsonPath("$.appliedAt").value(LocalDate.now().toString()));
    }

    @Test
    void applyWhileNotAwaitingReviewReturns409() throws Exception {
        when(registry.find("a1")).thenReturn(Optional.of(new Analysis("a1", "cv.pdf", "text", "job", "qwen")));

        mvc.perform(post("/api/analyses/a1/apply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedSuggestionIds\":[\"s1\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_AWAITING_REVIEW"));
    }

    @Test
    void applyWithEmptyListReturns422() throws Exception {
        when(registry.find("a1")).thenReturn(Optional.of(awaitingReview()));

        mvc.perform(post("/api/analyses/a1/apply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedSuggestionIds\":[]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_SUGGESTIONS_APPROVED"));
    }

    @Test
    void applyWithUnknownIdsReturns422() throws Exception {
        when(registry.find("a1")).thenReturn(Optional.of(awaitingReview()));

        mvc.perform(post("/api/analyses/a1/apply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedSuggestionIds\":[\"s9\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_SUGGESTIONS_APPROVED"));
    }

    @Test
    void applyValidReturns202Rewriting() throws Exception {
        Analysis analysis = awaitingReview();
        when(registry.find("a1")).thenReturn(Optional.of(analysis));

        mvc.perform(post("/api/analyses/a1/apply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedSuggestionIds\":[\"s1\"]}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.stage").value("REWRITING"))
                .andExpect(jsonPath("$.progress").value(65));

        verify(pipeline).apply(analysis);
    }

    @Test
    void retryWhileNotFailedOrCancelledReturns409() throws Exception {
        when(registry.find("a1")).thenReturn(Optional.of(awaitingReview()));

        mvc.perform(post("/api/analyses/a1/retry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_RETRYABLE"));
    }

    @Test
    void retryCancelledResumesAtTheInterruptedStage() throws Exception {
        Analysis analysis = new Analysis("a1", "cv.pdf", "text", "job", "qwen");
        analysis.transition(AnalysisStage.REWRITING, 65);
        analysis.cancel();
        when(registry.find("a1")).thenReturn(Optional.of(analysis));

        mvc.perform(post("/api/analyses/a1/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.stage").value("REWRITING"))
                .andExpect(jsonPath("$.errorCode").doesNotExist());

        verify(pipeline).retry(analysis);
    }

    @Test
    void cancelRunningAbortsTheRunAndReturns200() throws Exception {
        Analysis analysis = new Analysis("a1", "cv.pdf", "text", "job", "qwen");
        analysis.transition(AnalysisStage.EVALUATING, 15);
        when(registry.find("a1")).thenReturn(Optional.of(analysis));

        mvc.perform(post("/api/analyses/a1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("CANCELLED"))
                .andExpect(jsonPath("$.interruptedStage").value("EVALUATING"));

        verify(runs).abort("a1");
    }

    @Test
    void cancelAlreadyCancelledIsIdempotent() throws Exception {
        Analysis analysis = new Analysis("a1", "cv.pdf", "text", "job", "qwen");
        analysis.transition(AnalysisStage.EVALUATING, 15);
        analysis.cancel();
        when(registry.find("a1")).thenReturn(Optional.of(analysis));

        mvc.perform(post("/api/analyses/a1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("CANCELLED"));

        verify(runs, never()).abort(any());
    }

    @Test
    void cancelAtReviewGateReturns409() throws Exception {
        when(registry.find("a1")).thenReturn(Optional.of(awaitingReview()));

        mvc.perform(post("/api/analyses/a1/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_CANCELLABLE"));

        verify(runs, never()).abort(any());
    }

    @Test
    void retryFailedReturns202BackAtFailedStage() throws Exception {
        Analysis analysis = new Analysis("a1", "cv.pdf", "text", "job", "qwen");
        analysis.fail("LLM_INVALID_OUTPUT", "boom", AnalysisStage.EVALUATING);
        when(registry.find("a1")).thenReturn(Optional.of(analysis));

        mvc.perform(post("/api/analyses/a1/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.stage").value("EVALUATING"))
                .andExpect(jsonPath("$.errorCode").doesNotExist());

        verify(pipeline).retry(analysis);
    }

    @Test
    void resultBeforeCompletionReturns409() throws Exception {
        when(registry.find("a1")).thenReturn(Optional.of(awaitingReview()));

        mvc.perform(get("/api/analyses/a1/result"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_READY"));
    }

    @Test
    void resultCompletedServesPdf() throws Exception {
        Path file = Files.createTempFile("analysis-result-", ".pdf");
        Files.write(file, TestPdfs.pdf("Improved CV"));
        Analysis analysis = new Analysis("a1", "cv.pdf", "text", "job", "qwen");
        analysis.setResultFile(file);
        analysis.complete();
        when(registry.find("a1")).thenReturn(Optional.of(analysis));

        mvc.perform(get("/api/analyses/a1/result"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PDF_VALUE))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("cv-improved-a1.pdf")));
    }

    @Test
    void deleteUnknownReturns404() throws Exception {
        when(registry.find("nope")).thenReturn(Optional.empty());

        mvc.perform(delete("/api/analyses/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
    }

    @Test
    void deleteRemovesAndReturns204() throws Exception {
        Analysis analysis = awaitingReview();
        when(registry.find("a1")).thenReturn(Optional.of(analysis));

        mvc.perform(delete("/api/analyses/a1")).andExpect(status().isNoContent());

        verify(registry).remove("a1");
        verify(pipeline, never()).apply(any());
    }
}