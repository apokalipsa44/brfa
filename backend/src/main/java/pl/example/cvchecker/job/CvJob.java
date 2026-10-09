package pl.example.cvchecker.job;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import pl.example.cvchecker.llm.CvAnalysis;

/**
 * In-memory state of one CV job (plan §6.4). Thread-safe: stage transitions and event
 * appends happen under {@link #eventLock}; the SseHub uses the same lock for replay, so
 * events are neither lost nor duplicated (plan §6.6).
 */
public class CvJob {

    private final String id;
    private final Instant createdAt;
    private final Path originalFile;
    private final String jobDescription;
    private final List<JobEvent> events = new ArrayList<>();

    /** Single lock guarding {@link #events} and stage transitions. */
    public final Object eventLock = new Object();

    private volatile Stage stage = Stage.QUEUED;
    private volatile Path resultFile;
    private volatile CvAnalysis analysis;
    private volatile String errorCode;
    private volatile String errorMessage;

    public CvJob(String id, String jobDescription, Path originalFile) {
        this.id = id;
        this.jobDescription = jobDescription;
        this.originalFile = originalFile;
        this.createdAt = Instant.now();
    }

    /** Moves the job to {@code newStage} and appends the status event. Returns the stored event. */
    public JobEvent appendStatus(Stage newStage, String message, Integer progress) {
        synchronized (eventLock) {
            this.stage = newStage;
            JobEvent event = new JobEvent(events.size() + 1, id, newStage, message, progress, Instant.now(), null);
            events.add(event);
            return event;
        }
    }

    /** Moves the job to FAILED and appends the terminal failed event. */
    public JobEvent fail(String code, String message) {
        synchronized (eventLock) {
            this.stage = Stage.FAILED;
            this.errorCode = code;
            this.errorMessage = message;
            JobEvent event = new JobEvent(events.size() + 1, id, Stage.FAILED, message, null, Instant.now(), code);
            events.add(event);
            return event;
        }
    }

    public void setAnalysis(CvAnalysis analysis) {
        this.analysis = analysis;
    }

    public void setResultFile(Path resultFile) {
        this.resultFile = resultFile;
    }

    public String id() {
        return id;
    }

    public Stage stage() {
        return stage;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Path originalFile() {
        return originalFile;
    }

    public Path resultFile() {
        return resultFile;
    }

    public String jobDescription() {
        return jobDescription;
    }

    public CvAnalysis analysis() {
        return analysis;
    }

    public String errorCode() {
        return errorCode;
    }

    public String errorMessage() {
        return errorMessage;
    }

    /** Snapshot of the event buffer. Caller must hold {@link #eventLock}. */
    List<JobEvent> eventsUnderLock() {
        return Collections.unmodifiableList(events);
    }
}
