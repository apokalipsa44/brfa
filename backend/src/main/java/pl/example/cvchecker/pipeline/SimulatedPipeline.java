package pl.example.cvchecker.pipeline;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import pl.example.cvchecker.job.CvJob;
import pl.example.cvchecker.job.JobEvent;
import pl.example.cvchecker.job.Stage;
import pl.example.cvchecker.sse.SseHub;

/**
 * Simulated pipeline (milestone 4): walks the stages with fixed delays so event communication can be
 * tested without a local LLM. Replaced by the real PDFBox/LLM steps in milestones 7+.
 */
@Component
public class SimulatedPipeline {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPipeline.class);

    private final SseHub hub;
    private final Duration stageDelay;

    public SimulatedPipeline(SseHub hub,
                             @Value("${cvchecker.pipeline.stage-delay:2s}") Duration stageDelay) {
        this.hub = hub;
        this.stageDelay = stageDelay;
    }

    /** Emits QUEUED and runs the stages on the dedicated pipeline executor (plan §6.7). */
    @Async("pipelineExecutor")
    public void start(CvJob job) {
        try {
            append(job, Stage.QUEUED, "Queued for processing", 0);
            sleep();
            append(job, Stage.EXTRACTING_TEXT, "Extracting text from the PDF", 10);
            sleep();
            append(job, Stage.ANALYZING, "Analyzing CV match against the job posting", 35);
            sleep();
            append(job, Stage.REWRITING, "Rewriting sections with targeted improvements", 65);
            sleep();
            append(job, Stage.GENERATING_PDF, "Generating the improved PDF from the ATS template", 90);
            complete(job);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(job, "INTERNAL_ERROR", "Pipeline interrupted.");
        } catch (Exception e) {
            log.error("Simulated pipeline failed for job {}", job.id(), e);
            fail(job, "INTERNAL_ERROR", "Unexpected error while processing the CV.");
        }
    }

    /** Appends a status event and dispatches it in one critical section (plan §6.6). */
    private void append(CvJob job, Stage stage, String message, int progress) {
        JobEvent event;
        synchronized (job.eventLock) { // reentrant: appendStatus locks the same monitor
            event = job.appendStatus(stage, message, progress);
            hub.dispatch(job, event);
        }
    }

    private void complete(CvJob job) {
        JobEvent event;
        synchronized (job.eventLock) {
            event = job.appendStatus(Stage.COMPLETED, "Improved CV ready", 100);
            hub.dispatch(job, event);
        }
        log.info("Simulated pipeline completed for job {}", job.id());
    }

    private void fail(CvJob job, String code, String message) {
        JobEvent event;
        synchronized (job.eventLock) {
            event = job.fail(code, message);
            hub.dispatch(job, event);
        }
        log.error("Simulated pipeline failed for job {}: {}", job.id(), message);
    }

    private void sleep() throws InterruptedException {
        Thread.sleep(stageDelay.toMillis());
    }
}
