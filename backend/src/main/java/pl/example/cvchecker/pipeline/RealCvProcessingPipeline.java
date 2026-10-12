package pl.example.cvchecker.pipeline;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import pl.example.cvchecker.job.CvJob;
import pl.example.cvchecker.job.JobEvent;
import pl.example.cvchecker.job.JobReport;
import pl.example.cvchecker.job.Stage;
import pl.example.cvchecker.llm.CvAnalysis;
import pl.example.cvchecker.llm.CvAnalysisService;
import pl.example.cvchecker.llm.CvRewriteResult;
import pl.example.cvchecker.llm.CvRewriteService;
import pl.example.cvchecker.llm.LlmInvalidOutputException;
import pl.example.cvchecker.pdf.CvPdfRenderer;
import pl.example.cvchecker.pdf.PdfNoTextException;
import pl.example.cvchecker.pdf.PdfTextExtractor;
import pl.example.cvchecker.pdf.PdfUnreadableException;
import pl.example.cvchecker.sse.SseHub;
import pl.example.cvchecker.storage.TempFileStorage;

/**
 * Real pipeline (plan §6.7): extract text → analyze (LLM #1) → rewrite (LLM #2) → render the
 * ATS-friendly PDF. The controller does not wait; every stage is reported as an SSE event and any
 * exception becomes a {@code failed} terminal event (never an unhandled executor crash).
 */
@Component
@ConditionalOnProperty(name = "cvchecker.pipeline.mode", havingValue = "real", matchIfMissing = true)
public class RealCvProcessingPipeline implements CvProcessingPipeline {

    private static final Logger log = LoggerFactory.getLogger(RealCvProcessingPipeline.class);

    private final PdfTextExtractor extractor;
    private final CvAnalysisService analysisService;
    private final CvRewriteService rewriteService;
    private final CvPdfRenderer renderer;
    private final SseHub hub;
    private final TempFileStorage storage;

    public RealCvProcessingPipeline(PdfTextExtractor extractor, CvAnalysisService analysisService,
            CvRewriteService rewriteService, CvPdfRenderer renderer, SseHub hub, TempFileStorage storage) {
        this.extractor = extractor;
        this.analysisService = analysisService;
        this.rewriteService = rewriteService;
        this.renderer = renderer;
        this.hub = hub;
        this.storage = storage;
    }

    @Override
    @Async("pipelineExecutor")
    public void start(CvJob job) {
        long startedAt = System.currentTimeMillis();
        try {
            append(job, Stage.QUEUED, "Queued for processing", 0);

            append(job, Stage.EXTRACTING_TEXT, "Extracting text from the PDF", 10);
            String cvText = extractor.extract(job.originalFile());

            append(job, Stage.ANALYZING, "Analyzing CV match against the job posting", 35);
            CvAnalysis analysis = analysisService.analyze(cvText, job.jobDescription(), job.model());
            job.setAnalysis(analysis);

            append(job, Stage.REWRITING, "Rewriting sections with targeted improvements", 65);
            CvRewriteResult rewrite = rewriteService.rewrite(cvText, job.jobDescription(), analysis, job.model());
            job.setRewriteResult(rewrite);
            job.setReport(new JobReport(analysis.matchScore(), analysis.matchedKeywords(),
                    analysis.missingKeywords(), analysis.suggestions(), analysis.summary(), rewrite.changes()));

            append(job, Stage.GENERATING_PDF, "Generating the improved PDF from the ATS template", 90);
            Path result = storage.root().resolve(job.id() + "-result.pdf");
            renderer.render(rewrite.document(), result);
            job.setResultFile(result);

            complete(job, startedAt);
        } catch (PdfNoTextException e) {
            fail(job, "PDF_NO_TEXT", e.getMessage(), startedAt);
        } catch (PdfUnreadableException e) {
            fail(job, "PDF_UNREADABLE", e.getMessage(), startedAt);
        } catch (LlmInvalidOutputException e) {
            log.warn("Job {} failed LLM step: {}", job.id(), e.getMessage());
            fail(job, "LLM_INVALID_OUTPUT", "The model returned invalid output. Please try again.", startedAt);
        } catch (Exception e) {
            log.error("Pipeline failed for job {}", job.id(), e);
            fail(job, "INTERNAL_ERROR", "Unexpected error while processing the CV.", startedAt);
        }
    }

    /** Appends a status event and dispatches it in one critical section (plan §6.6). */
    private void append(CvJob job, Stage stage, String message, int progress) {
        JobEvent event;
        synchronized (job.eventLock) { // reentrant: appendStatus locks the same monitor
            event = job.appendStatus(stage, message, progress);
            hub.dispatch(job, event);
        }
        log.info("Job {} stage={} model={}", job.id(), stage, job.model() == null ? "(default)" : job.model());
    }

    private void complete(CvJob job, long startedAt) {
        JobEvent event;
        synchronized (job.eventLock) {
            event = job.appendStatus(Stage.COMPLETED, "Improved CV ready", 100);
            hub.dispatch(job, event);
        }
        log.info("Pipeline completed job {} in {} ms", job.id(), System.currentTimeMillis() - startedAt);
    }

    private void fail(CvJob job, String code, String message, long startedAt) {
        JobEvent event;
        synchronized (job.eventLock) {
            event = job.fail(code, message);
            hub.dispatch(job, event);
        }
        log.warn("Pipeline failed job {} code={} after {} ms", job.id(), code,
                System.currentTimeMillis() - startedAt);
    }
}
