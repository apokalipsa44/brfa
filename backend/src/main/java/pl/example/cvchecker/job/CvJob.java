package pl.example.cvchecker.job;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import pl.example.cvchecker.llm.CvRewriteResult;
import pl.example.cvchecker.llm.Evaluation;

/**
 * In-memory state of one CV job (plan §6.4). Thread-safe: stage transitions and event
 * appends happen under {@link #eventLock}; the SseHub uses the same lock for replay, so
 * events are neither lost nor duplicated (plan §6.6).
 */
public class CvJob {

    private final String id;
    private final String fileName;
    private final Instant createdAt;
    private final Path originalFile;
    private final String jobDescription;
    private final String model;
    private final List<JobEvent> events = new ArrayList<>();

    /** Single lock guarding {@link #events} and stage transitions. */
    public final Object eventLock = new Object();

    private volatile Stage stage = Stage.QUEUED;
    private volatile Integer progress = 0;
    private volatile Path resultFile;
    private volatile Evaluation analysis;
    private volatile CvRewriteResult rewriteResult;
    private volatile JobReport report;
    private volatile String errorCode;
    private volatile String errorMessage;

    /** Notified after a stage change so the registry can persist history (docs/API.md §8). */
    private volatile Runnable changeListener;

    public CvJob(String id, String jobDescription, Path originalFile) {
        this(id, null, jobDescription, originalFile, null);
    }

    public CvJob(String id, String jobDescription, Path originalFile, String model) {
        this(id, null, jobDescription, originalFile, model);
    }

    public CvJob(String id, String fileName, String jobDescription, Path originalFile, String model) {
        this(id, fileName, jobDescription, originalFile, model, Instant.now());
    }

    private CvJob(String id, String fileName, String jobDescription, Path originalFile, String model,
            Instant createdAt) {
        this.id = id;
        this.fileName = fileName;
        this.jobDescription = jobDescription;
        this.originalFile = originalFile;
        this.model = (model == null || model.isBlank()) ? null : model;
        this.createdAt = createdAt;
    }

    /** Rebuilds a job restored from the persisted history (docs/API.md §8). */
    static CvJob restored(StoredJob stored) {
        CvJob job = new CvJob(stored.jobId(), stored.fileName(), null, null, stored.model(),
                stored.createdAt());
        job.stage = stored.stage() == null ? Stage.FAILED : stored.stage();
        job.progress = stored.progress();
        job.report = stored.report();
        job.errorCode = stored.errorCode();
        job.errorMessage = stored.errorMessage();
        // A job that was still running when the process died cannot be resumed: its temp files are gone.
        if (!job.isTerminal()) {
            job.stage = Stage.FAILED;
            job.errorCode = "INTERRUPTED";
            job.errorMessage = "The server stopped before this job finished.";
        }
        return job;
    }

    /** Snapshot of this job for persistence; the registry calls it on every change. */
    public StoredJob toStoredJob() {
        return new StoredJob(id, fileName, stage, createdAt, progress, model, errorCode, errorMessage, report);
    }

    /** Registers the persistence hook invoked after each stage change. */
    void setChangeListener(Runnable changeListener) {
        this.changeListener = changeListener;
    }

    private void notifyChanged() {
        Runnable listener = this.changeListener;
        if (listener != null) listener.run();
    }

    /** Moves the job to {@code newStage} and appends the status event. Returns the stored event. */
    public JobEvent appendStatus(Stage newStage, String message, Integer progress) {
        JobEvent event;
        synchronized (eventLock) {
            this.stage = newStage;
            if (progress != null) this.progress = progress;
            event = new JobEvent(events.size() + 1, id, newStage, message, progress, Instant.now(), null);
            events.add(event);
        }
        notifyChanged();
        return event;
    }

    /** Moves the job to FAILED and appends the terminal failed event. */
    public JobEvent fail(String code, String message) {
        JobEvent event;
        synchronized (eventLock) {
            this.stage = Stage.FAILED;
            this.errorCode = code;
            this.errorMessage = message;
            event = new JobEvent(events.size() + 1, id, Stage.FAILED, message, null, Instant.now(), code);
            events.add(event);
        }
        notifyChanged();
        return event;
    }

    public void setAnalysis(Evaluation analysis) {
        this.analysis = analysis;
    }

    public void setRewriteResult(CvRewriteResult rewriteResult) {
        this.rewriteResult = rewriteResult;
    }

    /** The report attached to the {@code completed} event and the snapshot endpoint. */
    public void setReport(JobReport report) {
        this.report = report;
    }

    public void setResultFile(Path resultFile) {
        this.resultFile = resultFile;
    }

    public String id() {
        return id;
    }

    /** Original CV file name shown on the job card; {@code null} for jobs restored before it existed. */
    public String fileName() {
        return fileName;
    }

    public Stage stage() {
        return stage;
    }

    public Integer progress() {
        return progress;
    }

    /** True once the job reached COMPLETED or FAILED; emitters are closed at that point (plan §6.6). */
    public boolean isTerminal() {
        return stage == Stage.COMPLETED || stage == Stage.FAILED;
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

    /** Model selected for this job, or {@code null} to use the configured default (plan §6.3.5). */
    public String model() {
        return model;
    }

    public Evaluation analysis() {
        return analysis;
    }

    public CvRewriteResult rewriteResult() {
        return rewriteResult;
    }

    public JobReport report() {
        return report;
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

    /** Events with id greater than {@code afterId}. Caller must hold {@link #eventLock}. */
    public List<JobEvent> eventsAfter(long afterId) {
        List<JobEvent> result = new ArrayList<>();
        for (JobEvent event : events) {
            if (event.id() > afterId) result.add(event);
        }
        return result;
    }
}
