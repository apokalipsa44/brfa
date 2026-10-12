package pl.example.cvchecker.analysis;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
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
 * local LLM. The default is {@link RealAnalysisPipeline}.
 */
@Component
@ConditionalOnProperty(name = "cvchecker.pipeline.mode", havingValue = "simulated")
public class SimulatedAnalysisPipeline implements AnalysisPipeline {

    private final TempFileStorage storage;
    private final CvPdfRenderer renderer;
    private final Duration stageDelay;

    public SimulatedAnalysisPipeline(TempFileStorage storage, CvPdfRenderer renderer,
            @Value("${cvchecker.pipeline.stage-delay:2s}") Duration stageDelay) {
        this.storage = storage;
        this.renderer = renderer;
        this.stageDelay = stageDelay;
    }

    @Override
    @Async("pipelineExecutor")
    public void start(Analysis analysis) {
        try {
            if (analysis.stage() != AnalysisStage.EVALUATING) {
                analysis.transition(AnalysisStage.EVALUATING, 15);
            }
            sleep();
            analysis.setEvaluation(evaluation());
            sleep();
            analysis.transition(AnalysisStage.AWAITING_REVIEW, 40);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            analysis.fail("INTERNAL_ERROR", "The pipeline was interrupted.", AnalysisStage.EVALUATING);
        } catch (Exception e) {
            analysis.fail("INTERNAL_ERROR", "Unexpected error while evaluating the CV.", AnalysisStage.EVALUATING);
        }
    }

    @Override
    @Async("pipelineExecutor")
    public void apply(Analysis analysis) {
        try {
            analysis.transition(AnalysisStage.GENERATING_PDF, 90);
            sleep();
            renderFakePdf(analysis);
            analysis.setAppliedChanges(List.of(new CvChange("Skills", "Docker, CI/CD",
                    "Docker, Kubernetes (basics), CI/CD", "Approved suggestion applied.")));
            analysis.complete();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            analysis.fail("INTERNAL_ERROR", "The pipeline was interrupted.", AnalysisStage.REWRITING);
        } catch (Exception e) {
            analysis.fail("INTERNAL_ERROR", "Unexpected error while rewriting the CV.", AnalysisStage.REWRITING);
        }
    }

    @Override
    @Async("pipelineExecutor")
    public void retry(Analysis analysis) {
        if (analysis.failedStage() == AnalysisStage.REWRITING) {
            apply(analysis); // self-invocation; runs inline on the same executor thread
        } else {
            start(analysis);
        }
    }

    /** Renders a trivial but valid selectable-text PDF as the result file. */
    private void renderFakePdf(Analysis analysis) throws IOException {
        Path result = storage.root().resolve(analysis.id() + "-result.pdf");
        CvDocument document = new CvDocument("Improved CV", null, null, null,
                "Improved by the simulated analysis pipeline.", List.of(), List.of(), List.of("Java", "Spring"),
                List.of(), List.of());
        renderer.render(document, result);
        analysis.setResultFile(result);
    }

    private static Evaluation evaluation() {
        return new Evaluation("Acme", "Senior Java Developer", 58,
                List.of("Java", "Spring Boot"), List.of("Kubernetes", "Terraform"),
                List.of(new Suggestion("Skills", "The posting asks for Kubernetes but the CV mentions Docker only.",
                        "Group the existing container keywords so the skills list covers Kubernetes-related terms already present.",
                        "Docker, CI/CD", "Docker, Kubernetes (basics), CI/CD", Priority.HIGH)),
                "The CV matches the core stack but lacks container-orchestration keywords.");
    }

    private void sleep() throws InterruptedException {
        Thread.sleep(stageDelay.toMillis());
    }
}