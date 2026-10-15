package pl.example.cvchecker.analysis;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import pl.example.cvchecker.llm.CvChange;
import pl.example.cvchecker.llm.CvDocument;
import pl.example.cvchecker.llm.Evaluation;
import pl.example.cvchecker.llm.Priority;
import pl.example.cvchecker.llm.Suggestion;
import pl.example.cvchecker.pdf.CvPdfRenderer;
import pl.example.cvchecker.storage.TempFileStorage;

/**
 * Simulated analysis pipeline (active with {@code cvchecker.pipeline.mode=simulated}): walks the
 * stages with fixed delays and a canned evaluation so the two-stage flow can be tested without a
 * local LLM. The default is {@link RealAnalysisPipeline}. Like the real one, work runs through
 * {@link AnalysisRuns} and stops quietly when cancelled.
 */
@Component
@ConditionalOnProperty(name = "cvchecker.pipeline.mode", havingValue = "simulated")
public class SimulatedAnalysisPipeline implements AnalysisPipeline {

    private final TempFileStorage storage;
    private final CvPdfRenderer renderer;
    private final AnalysisRuns runs;
    private final Duration stageDelay;

    public SimulatedAnalysisPipeline(TempFileStorage storage, CvPdfRenderer renderer, AnalysisRuns runs,
            @Value("${cvchecker.pipeline.stage-delay:2s}") Duration stageDelay) {
        this.storage = storage;
        this.renderer = renderer;
        this.runs = runs;
        this.stageDelay = stageDelay;
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
        runs.submit(analysis, () -> run(analysis));
    }

    private void run(Analysis analysis) {
        long token = analysis.attemptId();
        try {
            AnalysisStage stage = analysis.stage();
            if (stage == AnalysisStage.QUEUED || stage == AnalysisStage.FETCHING_POSTING
                    || stage == AnalysisStage.EVALUATING) {
                if (analysis.stage() == AnalysisStage.QUEUED) {
                    if (!analysis.attemptTransition(token, AnalysisStage.EVALUATING, 15)) return;
                }
                sleep();
                if (!analysis.isAttemptCurrent(token)) return;
                if (!analysis.attemptSetEvaluation(token, evaluation())) return;
                sleep();
                analysis.attemptTransition(token, AnalysisStage.AWAITING_REVIEW, 40);
                return;
            }
            if (stage == AnalysisStage.REWRITING || stage == AnalysisStage.GENERATING_PDF) {
                if (analysis.stage() == AnalysisStage.REWRITING) {
                    sleep();
                    if (!analysis.isAttemptCurrent(token)) return;
                }
                if (!analysis.attemptTransition(token, AnalysisStage.GENERATING_PDF, 90)) return;
                sleep();
                renderFakePdf(analysis, token);
                analysis.attemptSetAppliedChanges(token, List.of(new CvChange("Skills", "Docker, CI/CD",
                        "Docker, Kubernetes (basics), CI/CD", "Approved suggestion applied.")));
                analysis.attemptComplete(token);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // A cancelled run stops quietly; anything else is an unexpected interruption.
            if (analysis.isAttemptCurrent(token)) {
                analysis.attemptFail(token, "INTERNAL_ERROR", "The pipeline was interrupted.",
                        failedStageOf(analysis));
            }
        } catch (Exception e) {
            analysis.attemptFail(token, "INTERNAL_ERROR", "Unexpected error while analysing the CV.",
                    failedStageOf(analysis));
        }
    }

    private static AnalysisStage failedStageOf(Analysis analysis) {
        return switch (analysis.stage()) {
            case FETCHING_POSTING, REWRITING, GENERATING_PDF -> analysis.stage();
            default -> AnalysisStage.EVALUATING;
        };
    }

    /** Renders a trivial but valid selectable-text PDF as the result file. */
    private void renderFakePdf(Analysis analysis, long token) throws IOException {
        Path result = storage.root().resolve(analysis.id() + "-result.pdf");
        CvDocument document = new CvDocument("Improved CV", null, null, null, null, List.of(),
                "Improved by the simulated analysis pipeline.", List.of(), List.of(), List.of("Java", "Spring"),
                List.of(), List.of());
        renderer.render(document, result);
        analysis.attemptSetResultFile(token, result);
    }

    private static Evaluation evaluation() {
        return new Evaluation("Acme", "Senior Java Developer", 58,
                List.of("Java", "Spring Boot"), List.of("Kubernetes", "Terraform"),
                List.of(new Suggestion("Skills", "The posting asks for Kubernetes but the CV mentions Docker only.",
                        "Group the existing container keywords so the skills list covers Kubernetes-related terms already present.",
                        "Docker, CI/CD", "Docker, Kubernetes (basics), CI/CD", Priority.HIGH)),
                "The CV matches the core stack but lacks container-orchestration keywords.",
                "Backend role building payment APIs in Java and Spring Boot on a small platform team.");
    }

    private void sleep() throws InterruptedException {
        Thread.sleep(stageDelay.toMillis());
    }
}
