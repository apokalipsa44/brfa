package pl.example.cvchecker.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import pl.example.cvchecker.llm.CvChange;
import pl.example.cvchecker.llm.CvDocument;
import pl.example.cvchecker.llm.CvRewriteResult;
import pl.example.cvchecker.llm.CvRewriteService;
import pl.example.cvchecker.llm.Evaluation;
import pl.example.cvchecker.llm.EvaluationService;
import pl.example.cvchecker.llm.LlmAbortedException;
import pl.example.cvchecker.llm.LlmInvalidOutputException;
import pl.example.cvchecker.pdf.CvPdfRenderer;
import pl.example.cvchecker.posting.PostingException;
import pl.example.cvchecker.posting.PostingResolver;
import pl.example.cvchecker.posting.ResolvedPosting;
import pl.example.cvchecker.storage.TempFileStorage;

/**
 * Real two-stage analysis pipeline (docs/API.md §1.1): for a link analysis resolve the posting in
 * FETCHING_POSTING, then evaluate (LLM step #1) → pause at AWAITING_REVIEW → rewrite (LLM step #2,
 * approved suggestions only) → render the ATS PDF. Every stage change is an
 * {@link AnalysisUpdatedEvent}; any exception becomes a FAILED state with the failed stage
 * remembered for retry (never an unhandled executor crash).
 *
 * <p>Work runs through {@link AnalysisRuns} so it can be cancelled: the dispatcher switches on the
 * analysis' current stage, every write is guarded by the run's attempt id, and a cancelled run stops
 * quietly (state already CANCELLED) instead of failing.
 */
@Component
@ConditionalOnProperty(name = "cvchecker.pipeline.mode", havingValue = "real", matchIfMissing = true)
public class RealAnalysisPipeline implements AnalysisPipeline {

    private static final Logger log = LoggerFactory.getLogger(RealAnalysisPipeline.class);

    private final EvaluationService evaluationService;
    private final CvRewriteService rewriteService;
    private final CvPdfRenderer renderer;
    private final TempFileStorage storage;
    private final AnalysisRuns runs;
    private final PostingResolver postingResolver;

    public RealAnalysisPipeline(EvaluationService evaluationService, CvRewriteService rewriteService,
            CvPdfRenderer renderer, TempFileStorage storage, AnalysisRuns runs, PostingResolver postingResolver) {
        this.evaluationService = evaluationService;
        this.rewriteService = rewriteService;
        this.renderer = renderer;
        this.storage = storage;
        this.runs = runs;
        this.postingResolver = postingResolver;
    }

    @Override
    public void start(Analysis analysis) {
        runs.submit(analysis, () -> run(analysis));
    }

    @Override
    public void apply(Analysis analysis) {
        runs.submit(analysis, () -> run(analysis));
    }

    @Override
    public void retry(Analysis analysis) {
        log.info("Analysis {} retrying from stage {}", analysis.id(), analysis.stage());
        runs.submit(analysis, () -> run(analysis));
    }

    /** Runs whichever stage the analysis is currently in (idempotent; resumes after a cancel). */
    private void run(Analysis analysis) {
        long token = analysis.attemptId();
        try {
            AnalysisStage stage = analysis.stage();
            if (stage == AnalysisStage.QUEUED || stage == AnalysisStage.FETCHING_POSTING
                    || stage == AnalysisStage.EVALUATING) {
                runEvaluation(analysis, token);
            }
            if (!analysis.isAttemptCurrent(token)) {
                return;
            }
            AnalysisStage afterEvaluation = analysis.stage();
            if (afterEvaluation == AnalysisStage.REWRITING || afterEvaluation == AnalysisStage.GENERATING_PDF) {
                runRewrite(analysis, token);
            }
        } catch (LlmAbortedException e) {
            // The run was cancelled: the CANCELLED state is already recorded; drop quietly.
            log.debug("Analysis {} run aborted at {}", analysis.id(), analysis.stage());
        } catch (LlmInvalidOutputException e) {
            fail(analysis, token, "LLM_INVALID_OUTPUT", "The model returned invalid output. Please try again.", e);
        } catch (Exception e) {
            fail(analysis, token, "INTERNAL_ERROR", "Unexpected error while analysing the CV.", e);
        }
    }

    private void fail(Analysis analysis, long token, String code, String message, Exception cause) {
        AnalysisStage failedStage = failedStageOf(analysis);
        if (analysis.attemptFail(token, code, message, failedStage)) {
            log.error("Analysis {} failed at {}", analysis.id(), failedStage, cause);
        }
    }

    /** FETCHING_POSTING → EVALUATING (LLM step #1) → suggestions scored and staged for review. */
    private void runEvaluation(Analysis analysis, long token) {
        if (analysis.sourceUrl() != null && analysis.postingText() == null) {
            if (!fetchPosting(analysis, token)) return;
        }
        if (!analysis.isAttemptCurrent(token)) return;
        if (analysis.stage() == AnalysisStage.QUEUED || analysis.stage() == AnalysisStage.FETCHING_POSTING) {
            if (!analysis.attemptTransition(token, AnalysisStage.EVALUATING, 15)) return;
        }
        if (!analysis.isAttemptCurrent(token)) return;
        Evaluation evaluation = evaluationService.evaluate(analysis.cvText(), analysis.postingText(),
                analysis.modelId());
        if (!analysis.isAttemptCurrent(token)) return;
        if (!analysis.attemptSetEvaluation(token, evaluation)) return;
        analysis.attemptTransition(token, AnalysisStage.AWAITING_REVIEW, 40);
    }

    /**
     * FETCHING_POSTING (docs/API.md §4.1): resolves the link to posting text. A fetch failure fails
     * the stage with the contract's error code (retry re-runs FETCHING_POSTING); a cancelled run
     * stops quietly via the attempt guards.
     */
    private boolean fetchPosting(Analysis analysis, long token) {
        if (analysis.stage() != AnalysisStage.FETCHING_POSTING) {
            if (!analysis.attemptTransition(token, AnalysisStage.FETCHING_POSTING, 5)) return false;
        }
        analysis.attemptStageMessage(token, "Fetching the page");
        try {
            ResolvedPosting posting = postingResolver.resolve(analysis.sourceUrl(), analysis.modelId());
            return analysis.attemptSetPosting(token, posting.text(), posting.source(), posting.truncated(),
                    posting.employer(), posting.position());
        } catch (PostingException e) {
            fail(analysis, token, e.code().name(), e.getMessage(), e);
            return false;
        } catch (IOException e) {
            fail(analysis, token, "FETCH_FAILED", "The page could not be fetched.", e);
            return false;
        }
    }

    /** LLM step #2 (approved suggestions only) → PDF; resumes from the cached rewrite if possible. */
    private void runRewrite(Analysis analysis, long token) throws IOException {
        Evaluation evaluation = analysis.evaluation();
        if (evaluation == null) {
            throw new IllegalStateException("Cannot rewrite analysis " + analysis.id() + " without an evaluation.");
        }
        CvDocument document = analysis.rewrittenDocument();
        if (document == null) {
            if (analysis.stage() != AnalysisStage.REWRITING) {
                if (!analysis.attemptTransition(token, AnalysisStage.REWRITING, 65)) return;
            }
            if (!analysis.isAttemptCurrent(token)) return;
            CvRewriteResult rewrite = rewriteService.rewrite(analysis.cvText(), analysis.postingText(), evaluation,
                    analysis.approvedSuggestions(), analysis.modelId());
            if (!analysis.isAttemptCurrent(token)) return;
            List<CvChange> changes = rewrite.changes();
            analysis.attemptSetAppliedChanges(token, changes);
            analysis.attemptSetRewrittenDocument(token, rewrite.document());
            document = rewrite.document();
        }
        if (!analysis.isAttemptCurrent(token)) return;
        if (analysis.stage() != AnalysisStage.GENERATING_PDF) {
            if (!analysis.attemptTransition(token, AnalysisStage.GENERATING_PDF, 90)) return;
        }
        if (Thread.currentThread().isInterrupted()) return;
        Path result = storage.root().resolve(analysis.id() + "-result.pdf");
        renderer.render(document, result);
        if (!analysis.isAttemptCurrent(token)) {
            deleteQuietly(result);
            return;
        }
        analysis.attemptSetResultFile(token, result);
        analysis.attemptComplete(token);
    }

    private static AnalysisStage failedStageOf(Analysis analysis) {
        return switch (analysis.stage()) {
            case FETCHING_POSTING, REWRITING, GENERATING_PDF -> analysis.stage();
            default -> AnalysisStage.EVALUATING;
        };
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete orphaned result file {}", path.getFileName());
        }
    }
}
