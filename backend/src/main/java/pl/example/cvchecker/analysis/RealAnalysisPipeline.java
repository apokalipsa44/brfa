package pl.example.cvchecker.analysis;

import java.io.IOException;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import pl.example.cvchecker.llm.CvRewriteResult;
import pl.example.cvchecker.llm.CvRewriteService;
import pl.example.cvchecker.llm.Evaluation;
import pl.example.cvchecker.llm.EvaluationService;
import pl.example.cvchecker.llm.LlmInvalidOutputException;
import pl.example.cvchecker.pdf.CvPdfRenderer;
import pl.example.cvchecker.storage.TempFileStorage;

/**
 * Real two-stage analysis pipeline (docs/API.md §1.1): evaluate (LLM step #1) → pause at
 * AWAITING_REVIEW → rewrite (LLM step #2, approved suggestions only) → render the ATS PDF. Every
 * stage change is an {@link AnalysisUpdatedEvent}; any exception becomes a FAILED state with the
 * failed stage remembered for retry (never an unhandled executor crash).
 */
@Component
@ConditionalOnProperty(name = "cvchecker.pipeline.mode", havingValue = "real", matchIfMissing = true)
public class RealAnalysisPipeline implements AnalysisPipeline {

    private static final Logger log = LoggerFactory.getLogger(RealAnalysisPipeline.class);

    private final EvaluationService evaluationService;
    private final CvRewriteService rewriteService;
    private final CvPdfRenderer renderer;
    private final TempFileStorage storage;

    public RealAnalysisPipeline(EvaluationService evaluationService, CvRewriteService rewriteService,
            CvPdfRenderer renderer, TempFileStorage storage) {
        this.evaluationService = evaluationService;
        this.rewriteService = rewriteService;
        this.renderer = renderer;
        this.storage = storage;
    }

    @Override
    @Async("pipelineExecutor")
    public void start(Analysis analysis) {
        try {
            runEvaluation(analysis);
            log.info("Analysis {} waiting for review (model={})", analysis.id(), analysis.modelId());
        } catch (LlmInvalidOutputException e) {
            log.warn("Analysis {} failed evaluation: {}", analysis.id(), e.getMessage());
            analysis.fail("LLM_INVALID_OUTPUT", "The model returned invalid output. Please try again.",
                    AnalysisStage.EVALUATING);
        } catch (Exception e) {
            log.error("Analysis {} failed evaluation", analysis.id(), e);
            analysis.fail("INTERNAL_ERROR", "Unexpected error while evaluating the CV.", AnalysisStage.EVALUATING);
        }
    }

    @Override
    @Async("pipelineExecutor")
    public void apply(Analysis analysis) {
        try {
            runRewrite(analysis);
        } catch (LlmInvalidOutputException e) {
            log.warn("Analysis {} failed rewrite: {}", analysis.id(), e.getMessage());
            analysis.fail("LLM_INVALID_OUTPUT", "The model returned invalid output. Please try again.",
                    AnalysisStage.REWRITING);
        } catch (Exception e) {
            log.error("Analysis {} failed rewrite", analysis.id(), e);
            analysis.fail("INTERNAL_ERROR", "Unexpected error while rewriting the CV.", AnalysisStage.REWRITING);
        }
    }

    @Override
    @Async("pipelineExecutor")
    public void retry(Analysis analysis) {
        log.info("Analysis {} retrying failed stage {}", analysis.id(), analysis.stage());
        if (analysis.failedStage() == AnalysisStage.REWRITING) {
            apply(analysis); // self-invocation; runs inline on the same executor thread
        } else {
            start(analysis);
        }
    }

    /** LLM step #1 → suggestions scored and staged for review. */
    private void runEvaluation(Analysis analysis) {
        if (analysis.stage() != AnalysisStage.EVALUATING) {
            analysis.transition(AnalysisStage.EVALUATING, 15);
        }
        Evaluation evaluation = evaluationService.evaluate(analysis.cvText(), analysis.jobPosting(),
                analysis.modelId());
        analysis.setEvaluation(evaluation);
        analysis.transition(AnalysisStage.AWAITING_REVIEW, 40);
    }

    /** LLM step #2 (approved suggestions only) → PDF. Runs when already REWRITING. */
    private void runRewrite(Analysis analysis) throws IOException {
        Evaluation evaluation = analysis.evaluation();
        if (evaluation == null) {
            throw new IllegalStateException("Cannot rewrite analysis " + analysis.id() + " without an evaluation.");
        }
        if (analysis.stage() != AnalysisStage.REWRITING) {
            analysis.transition(AnalysisStage.REWRITING, 65);
        }
        CvRewriteResult rewrite = rewriteService.rewrite(analysis.cvText(), analysis.jobPosting(), evaluation,
                analysis.approvedSuggestions(), analysis.modelId());
        analysis.setAppliedChanges(rewrite.changes());
        analysis.transition(AnalysisStage.GENERATING_PDF, 90);
        Path result = storage.root().resolve(analysis.id() + "-result.pdf");
        renderer.render(rewrite.document(), result);
        analysis.setResultFile(result);
        analysis.complete();
    }
}