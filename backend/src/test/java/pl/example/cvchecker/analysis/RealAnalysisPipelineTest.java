package pl.example.cvchecker.analysis;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import pl.example.cvchecker.llm.CvRewriteService;
import pl.example.cvchecker.llm.Evaluation;
import pl.example.cvchecker.llm.EvaluationService;
import pl.example.cvchecker.llm.LlmTokenSink;
import pl.example.cvchecker.llm.Priority;
import pl.example.cvchecker.llm.Suggestion;
import pl.example.cvchecker.pdf.CvPdfRenderer;
import pl.example.cvchecker.posting.PostingException;
import pl.example.cvchecker.posting.PostingResolver;
import pl.example.cvchecker.posting.ResolvedPosting;
import pl.example.cvchecker.storage.TempFileStorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FETCHING_POSTING wiring (docs/API.md §4.1): a link analysis resolves its posting through
 * {@link PostingResolver} before evaluation and fails the stage with the fetch error code when the
 * page is unusable; a pasted analysis never fetches.
 */
class RealAnalysisPipelineTest {

    private EvaluationService evaluationService;
    private PostingResolver resolver;
    private RealAnalysisPipeline pipeline;

    @BeforeEach
    void setUp() throws Exception {
        evaluationService = mock(EvaluationService.class);
        resolver = mock(PostingResolver.class);
        AnalysisRuns runs = mock(AnalysisRuns.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(runs).submit(any(), any());
        pipeline = new RealAnalysisPipeline(evaluationService, mock(CvRewriteService.class),
                mock(CvPdfRenderer.class), new TempFileStorage(), runs, resolver,
                mock(ApplicationEventPublisher.class));
    }

    @Test
    void linkAnalysisFetchesThePostingBeforeEvaluation() throws Exception {
        when(resolver.resolve("https://example.com/job", "qwen")).thenReturn(
                new ResolvedPosting("Backend developer wanted", PostingSource.JSON_LD, false, "Acme", "Dev"));
        when(evaluationService.evaluate(eq("cv text"), eq("Backend developer wanted"), eq("qwen"),
                any(LlmTokenSink.class))).thenReturn(evaluation());
        Analysis analysis = Analysis.fromUrl("a1", "cv.pdf", "cv text", "https://example.com/job", "qwen");

        pipeline.start(analysis);

        assertThat(analysis.stage()).isEqualTo(AnalysisStage.AWAITING_REVIEW);
        assertThat(analysis.postingText()).isEqualTo("Backend developer wanted");
        assertThat(analysis.postingSource()).isEqualTo(PostingSource.JSON_LD);
        assertThat(analysis.title()).isEqualTo("Acme · Dev");
        verify(evaluationService).evaluate(eq("cv text"), eq("Backend developer wanted"), eq("qwen"),
                any(LlmTokenSink.class));
    }

    @Test
    void postingFetchFailureFailsTheFetchingStageAndRetryResumesThere() throws Exception {
        when(resolver.resolve(anyString(), anyString()))
                .thenThrow(new PostingException(PostingException.ErrorCode.POSTING_NOT_FOUND, "No posting found."));
        Analysis analysis = Analysis.fromUrl("a1", "cv.pdf", "cv text", "https://example.com/job", "qwen");

        pipeline.start(analysis);

        assertThat(analysis.stage()).isEqualTo(AnalysisStage.FAILED);
        assertThat(analysis.errorCode()).isEqualTo("POSTING_NOT_FOUND");
        assertThat(analysis.resetForRetry()).isTrue();
        assertThat(analysis.stage()).isEqualTo(AnalysisStage.FETCHING_POSTING);
        verify(evaluationService, never()).evaluate(any(), any(), any(), any());
    }

    @Test
    void pastedAnalysisSkipsTheFetch() throws Exception {
        when(evaluationService.evaluate(eq("cv text"), eq("pasted posting"), eq("qwen"),
                any(LlmTokenSink.class))).thenReturn(evaluation());
        Analysis analysis = new Analysis("a1", "cv.pdf", "cv text", "pasted posting", "qwen");

        pipeline.start(analysis);

        assertThat(analysis.stage()).isEqualTo(AnalysisStage.AWAITING_REVIEW);
        verify(resolver, never()).resolve(any(), any());
    }

    private static Evaluation evaluation() {
        return new Evaluation("Acme", "Dev", 50, List.of(), List.of(),
                List.of(new Suggestion("Skills", "problem", "proposal", "before", "after", Priority.MEDIUM)),
                "summary", null);
    }
}
