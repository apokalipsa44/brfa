package pl.example.cvchecker.analysis;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import pl.example.cvchecker.api.AnalysisSummary;
import pl.example.cvchecker.llm.CvChange;
import pl.example.cvchecker.llm.CvDocument;
import pl.example.cvchecker.llm.Evaluation;
import pl.example.cvchecker.llm.Suggestion;

/**
 * In-memory state of one analysis (docs/API.md §4). Thread-safe: stage transitions happen under
 * {@link #eventLock}; {@code changeListener} fires after every change so the registry can broadcast
 * {@code analysis.updated} events. The analysis snapshots the CV text and the active model id at
 * creation, so replacing the CV or switching the model never disturbs an existing analysis.
 *
 * <p>Cancellation and stale runs (docs/API.md §1.1): every run carries an {@code attemptId}. The
 * guarded {@code attempt*} mutators only take effect while the writing run's token is still the
 * current attempt and the analysis was not cancelled, so a cancelled task that finishes anyway —
 * or a cancel racing a stage completion — cannot overwrite the state.
 */
public class Analysis {

    /** Title fallback before evaluation finishes (docs/API.md §6.2). */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** Single lock guarding stage transitions so events fire exactly once per change. */
    private final Object eventLock = new Object();

    private final String id;
    private final String cvFileName;
    private final String cvText;
    private final String sourceUrl;
    private final String modelId;
    private final Instant createdAt;

    private volatile AnalysisStage stage = AnalysisStage.QUEUED;
    private volatile int progress = 0;
    private volatile Instant updatedAt;
    private volatile String title;
    private volatile boolean userEditedTitle;
    private volatile String userDescription;
    private volatile LocalDate appliedAt;
    private volatile Integer score;
    private volatile Evaluation evaluation;
    private volatile List<AnalysisSuggestion> suggestions = List.of();
    private volatile List<String> approvedSuggestionIds;
    private volatile List<CvChange> appliedChanges;
    private volatile CvDocument rewrittenDocument;
    private volatile Path resultFile;
    private volatile String errorCode;
    private volatile String errorMessage;
    private volatile AnalysisStage failedStage;
    private volatile AnalysisStage interruptedStage;
    private volatile long attemptId = 1;
    private volatile String stageMessage;
    private volatile String postingText;
    private volatile PostingSource postingSource;
    private volatile boolean postingTruncated;
    private volatile String postingEmployer;
    private volatile String postingPosition;

    /** Notified after every change so the registry can publish analysis.updated events. */
    private volatile Runnable changeListener;

    /** Pasted posting: the text is known from the start (docs/API.md §4). */
    public Analysis(String id, String cvFileName, String cvText, String jobPosting, String modelId) {
        this(id, cvFileName, cvText, null, jobPosting, modelId, Instant.now());
    }

    /** Test/creation seam with a fixed timestamp (docs/API.md §6.2 date fallback). */
    Analysis(String id, String cvFileName, String cvText, String jobPosting, String modelId, Instant createdAt) {
        this(id, cvFileName, cvText, null, jobPosting, modelId, createdAt);
    }

    /** Link posting: the text is resolved by the FETCHING_POSTING stage (docs/API.md §4.1). */
    public static Analysis fromUrl(String id, String cvFileName, String cvText, String sourceUrl, String modelId) {
        return new Analysis(id, cvFileName, cvText, sourceUrl, null, modelId, Instant.now());
    }

    private Analysis(String id, String cvFileName, String cvText, String sourceUrl, String postingText,
            String modelId, Instant createdAt) {
        this.id = id;
        this.cvFileName = cvFileName;
        this.cvText = cvText;
        this.sourceUrl = sourceUrl;
        this.postingText = postingText;
        this.postingSource = postingText == null ? null : PostingSource.PASTED;
        this.modelId = modelId;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    /** Rebuilds an analysis from its persisted snapshot (docs/API.md §8). */
    public static Analysis fromState(AnalysisState state) {
        Analysis analysis = new Analysis(state.id(), state.cvFileName(), state.cvText(), state.sourceUrl(),
                state.postingText(), state.modelId(), state.createdAt());
        analysis.updatedAt = state.updatedAt();
        analysis.stage = state.stage();
        analysis.progress = state.progress();
        analysis.title = state.title();
        analysis.userEditedTitle = state.userEditedTitle();
        analysis.userDescription = state.userDescription();
        analysis.appliedAt = state.appliedAt();
        analysis.score = state.score();
        analysis.evaluation = state.evaluation();
        analysis.suggestions = state.suggestions() == null ? List.of() : List.copyOf(state.suggestions());
        analysis.approvedSuggestionIds = state.approvedSuggestionIds();
        analysis.appliedChanges = state.appliedChanges();
        analysis.rewrittenDocument = state.rewrittenDocument();
        analysis.resultFile = state.resultFile() == null ? null : Path.of(state.resultFile());
        analysis.errorCode = state.errorCode();
        analysis.errorMessage = state.errorMessage();
        analysis.failedStage = state.failedStage();
        analysis.interruptedStage = state.interruptedStage();
        analysis.attemptId = state.attemptId();
        analysis.postingSource = state.postingSource();
        analysis.postingTruncated = state.postingTruncated();
        analysis.postingEmployer = state.postingEmployer();
        analysis.postingPosition = state.postingPosition();
        return analysis;
    }

    /** Snapshots every persistent field for storage (docs/API.md §8). */
    public AnalysisState toState() {
        return new AnalysisState(id, cvFileName, cvText, sourceUrl, modelId, createdAt, updatedAt, stage,
                progress, title, userEditedTitle, userDescription, appliedAt, score, evaluation,
                suggestions, approvedSuggestionIds, appliedChanges, rewrittenDocument,
                resultFile == null ? null : resultFile.toString(), errorCode, errorMessage, failedStage,
                interruptedStage, attemptId, postingText, postingSource, postingTruncated,
                postingEmployer, postingPosition);
    }

    void setChangeListener(Runnable changeListener) {
        this.changeListener = changeListener;
    }

    private void notifyChanged() {
        Runnable listener = this.changeListener;
        if (listener != null) listener.run();
    }

    /** True when {@code token} is still the current run and the analysis was not cancelled. */
    public boolean isAttemptCurrent(long token) {
        return token == attemptId && stage != AnalysisStage.CANCELLED;
    }

    private boolean attemptCurrentLocked(long token) {
        return token == attemptId && stage != AnalysisStage.CANCELLED;
    }

    /** Moves the analysis to {@code newStage} with the stage's indicative progress (§1.1). */
    public void transition(AnalysisStage newStage, int newProgress) {
        attemptTransition(attemptId, newStage, newProgress);
    }

    /**
     * Guarded transition used by a running pipeline (docs/API.md §1.1). Dropped (returns false) when
     * the run is stale or the analysis was cancelled.
     */
    public boolean attemptTransition(long token, AnalysisStage newStage, int newProgress) {
        synchronized (eventLock) {
            if (!attemptCurrentLocked(token)) return false;
            this.stage = newStage;
            this.progress = newProgress;
            this.stageMessage = null;
            this.updatedAt = Instant.now();
        }
        notifyChanged();
        return true;
    }

    /** Sets a transient sub-stage message (e.g. "Fetching the page"); guarded like a transition. */
    public boolean attemptStageMessage(long token, String message) {
        synchronized (eventLock) {
            if (!attemptCurrentLocked(token) || stage != AnalysisStage.FETCHING_POSTING) return false;
            this.stageMessage = message;
            this.updatedAt = Instant.now();
        }
        notifyChanged();
        return true;
    }

    /** Marks the analysis FAILED; {@code failedStage} remembers what to re-run on retry. */
    public void fail(String code, String message, AnalysisStage failedStage) {
        attemptFail(attemptId, code, message, failedStage);
    }

    /** Guarded failure used by a running pipeline (docs/API.md §1.1). */
    public boolean attemptFail(long token, String code, String message, AnalysisStage failedStage) {
        synchronized (eventLock) {
            if (!attemptCurrentLocked(token)) return false;
            this.stage = AnalysisStage.FAILED;
            this.errorCode = code;
            this.errorMessage = message;
            this.failedStage = failedStage;
            this.stageMessage = null;
            this.updatedAt = Instant.now();
        }
        notifyChanged();
        return true;
    }

    /** Stores the evaluation, scores it and assigns stable ids (s1..sN) to its suggestions. */
    public void setEvaluation(Evaluation evaluation) {
        attemptSetEvaluation(attemptId, evaluation);
    }

    /** Guarded evaluation write used by a running pipeline (docs/API.md §1.1). */
    public boolean attemptSetEvaluation(long token, Evaluation evaluation) {
        synchronized (eventLock) {
            if (!attemptCurrentLocked(token)) return false;
            this.evaluation = evaluation;
            this.score = evaluation.matchScore();
            this.suggestions = assignIds(evaluation.suggestions());
            this.updatedAt = Instant.now();
        }
        notifyChanged();
        return true;
    }

    /** Approves suggestions and starts stage 2 (called synchronously by {@code /apply}, §4). */
    public void beginRewrite(List<String> approvedIds) {
        synchronized (eventLock) {
            this.approvedSuggestionIds = List.copyOf(approvedIds);
            this.stage = AnalysisStage.REWRITING;
            this.progress = 65;
            this.stageMessage = null;
            this.errorCode = null;
            this.errorMessage = null;
            this.attemptId++;
            this.updatedAt = Instant.now();
        }
        notifyChanged();
    }

    /** Stores the rewrite result; kept so retry after a cancel during PDF generation can resume. */
    public void setAppliedChanges(List<CvChange> changes) {
        attemptSetAppliedChanges(attemptId, changes);
    }

    public void attemptSetAppliedChanges(long token, List<CvChange> changes) {
        synchronized (eventLock) {
            if (!attemptCurrentLocked(token)) return;
            this.appliedChanges = changes;
        }
    }

    public void attemptSetRewrittenDocument(long token, CvDocument document) {
        synchronized (eventLock) {
            if (!attemptCurrentLocked(token)) return;
            this.rewrittenDocument = document;
        }
    }

    public void setResultFile(Path resultFile) {
        attemptSetResultFile(attemptId, resultFile);
    }

    public void attemptSetResultFile(long token, Path resultFile) {
        synchronized (eventLock) {
            if (!attemptCurrentLocked(token)) return;
            this.resultFile = resultFile;
        }
    }

    /** Stores the resolved posting text and its provenance for a link analysis (docs/API.md §4.1). */
    public boolean attemptSetPosting(long token, String text, PostingSource source, boolean truncated,
            String employer, String position) {
        synchronized (eventLock) {
            if (!attemptCurrentLocked(token)) return false;
            this.postingText = text;
            this.postingSource = source;
            this.postingTruncated = truncated;
            if (employer != null && !employer.isBlank()) this.postingEmployer = employer.strip();
            if (position != null && !position.isBlank()) this.postingPosition = position.strip();
            this.updatedAt = Instant.now();
        }
        notifyChanged();
        return true;
    }

    public void complete() {
        attemptComplete(attemptId);
    }

    /** Guarded completion used by a running pipeline (docs/API.md §1.1). */
    public boolean attemptComplete(long token) {
        return attemptTransition(token, AnalysisStage.COMPLETED, 100);
    }

    /**
     * Cancels a queued or running analysis (docs/API.md §1.1). The interrupted stage is remembered so
     * retry can resume there.
     *
     * @return true when the analysis moved to CANCELLED; false when the current stage is not
     *         cancellable (the caller decides between the idempotent 200 and a 409)
     */
    public boolean cancel() {
        synchronized (eventLock) {
            if (!stage.isCancellable()) return false;
            this.interruptedStage = stage;
            this.stage = AnalysisStage.CANCELLED;
            this.stageMessage = null;
            this.errorCode = null;
            this.errorMessage = null;
            this.updatedAt = Instant.now();
        }
        notifyChanged();
        return true;
    }

    /**
     * Moves a FAILED or CANCELLED analysis back to the stage to re-run so retry can resume it (§4),
     * incrementing the attempt id so the previous run can no longer write.
     *
     * @return true when the analysis was reset; false when it was neither FAILED nor CANCELLED
     */
    public boolean resetForRetry() {
        synchronized (eventLock) {
            if (stage != AnalysisStage.FAILED && stage != AnalysisStage.CANCELLED) return false;
            AnalysisStage target = failedStage != null ? failedStage
                    : (interruptedStage != null ? interruptedStage : AnalysisStage.EVALUATING);
            this.stage = target;
            this.progress = progressFor(target);
            this.errorCode = null;
            this.errorMessage = null;
            this.failedStage = null;
            this.interruptedStage = null;
            this.stageMessage = null;
            this.attemptId++;
            this.updatedAt = Instant.now();
        }
        notifyChanged();
        return true;
    }

    private static int progressFor(AnalysisStage stage) {
        return switch (stage) {
            case QUEUED -> 0;
            case FETCHING_POSTING -> 5;
            case EVALUATING -> 15;
            case REWRITING -> 65;
            case GENERATING_PDF -> 90;
            default -> 0;
        };
    }

    /** Marks the title as user-edited; it is never auto-overwritten afterwards (§6.2). */
    public void rename(String newTitle) {
        synchronized (eventLock) {
            this.title = newTitle;
            this.userEditedTitle = true;
            this.updatedAt = Instant.now();
        }
        notifyChanged();
    }

    /**
     * Sets the user description override (PATCH {@code description}, docs/API.md §4). A null value
     * clears the override, so {@link #shortDescription()} falls back to the evaluation's value; a
     * set value is never overwritten by a re-evaluation.
     */
    public void setDescription(String description) {
        synchronized (eventLock) {
            this.userDescription = description;
            this.updatedAt = Instant.now();
        }
        notifyChanged();
    }

    /**
     * Marks the analysis as applied with today's date, keeping an existing date (PATCH
     * {@code applied:true}, docs/API.md §4). User-set: pipeline runs never change it.
     */
    public void markApplied() {
        synchronized (eventLock) {
            if (this.appliedAt == null) this.appliedAt = LocalDate.now();
            this.updatedAt = Instant.now();
        }
        notifyChanged();
    }

    /** Sets an explicit applied date (PATCH {@code appliedAt}, docs/API.md §4). */
    public void setApplied(LocalDate date) {
        synchronized (eventLock) {
            this.appliedAt = date;
            this.updatedAt = Instant.now();
        }
        notifyChanged();
    }

    /** Clears the applied marker (PATCH {@code applied:false}, docs/API.md §4). */
    public void clearApplied() {
        synchronized (eventLock) {
            this.appliedAt = null;
            this.updatedAt = Instant.now();
        }
        notifyChanged();
    }

    public AnalysisSummary summary() {
        return new AnalysisSummary(id, title(), stage, score, shortDescription(), progress, attemptId,
                interruptedStage, stageMessage, appliedAt, createdAt, updatedAt, errorCode, errorMessage);
    }

    /**
     * The user's description when they edited one, otherwise the evaluation's capped role
     * description; null before evaluation / when neither is set (docs/API.md §4/§6.4).
     */
    public String shortDescription() {
        if (userDescription != null) return userDescription;
        Evaluation current = evaluation;
        return current == null ? null : current.shortDescription();
    }

    /** The user description override, or null when the evaluation's value is shown (§4). */
    public String userDescription() {
        return userDescription;
    }

    /** Auto-generated "Employer · Position" once evaluation finishes, otherwise the date fallback. */
    public String title() {
        if (userEditedTitle && title != null) return title;
        if (evaluation != null) {
            String generated = joinNonBlank(" · ", evaluation.employer(), evaluation.position());
            if (!generated.isBlank()) return generated;
        }
        String fromPosting = joinNonBlank(" · ", postingEmployer, postingPosition);
        if (!fromPosting.isBlank()) return fromPosting;
        return "Untitled analysis · " + DATE.format(createdAt.atZone(ZoneId.systemDefault()));
    }

    /** True once the analysis reached COMPLETED, FAILED or CANCELLED (terminal states, §1.1). */
    public boolean isTerminal() {
        return stage.isTerminal();
    }

    public boolean hasSuggestion(String id) {
        return suggestions.stream().anyMatch(s -> s.id().equals(id));
    }

    /** The approved LLM suggestions in approval order (dependencies of LLM step #2). */
    public List<Suggestion> approvedSuggestions() {
        List<String> ids = approvedSuggestionIds == null ? List.of() : approvedSuggestionIds;
        List<AnalysisSuggestion> all = suggestions;
        return ids.stream()
                .flatMap(id -> all.stream().filter(s -> s.id().equals(id)))
                .map(s -> new Suggestion(s.section(), s.problem(), s.proposal(), s.before(), s.after(), s.priority()))
                .toList();
    }

    public String id() {
        return id;
    }

    /** CV file name shown in the workspace; snapshotted at creation (§2). */
    public String cvFileName() {
        return cvFileName;
    }

    public String cvText() {
        return cvText;
    }

    /** The link the user provided, or null for a pasted posting (§4). */
    public String sourceUrl() {
        return sourceUrl;
    }

    /** The resolved posting text used for evaluation; null for a link until FETCHING_POSTING succeeds. */
    public String postingText() {
        return postingText;
    }

    public PostingSource postingSource() {
        return postingSource;
    }

    public boolean postingTruncated() {
        return postingTruncated;
    }

    /** The model id active when the analysis was created (§3/§6.3). */
    public String modelId() {
        return modelId;
    }

    public AnalysisStage stage() {
        return stage;
    }

    public int progress() {
        return progress;
    }

    /** Monotonic run counter (docs/API.md §1.1); incremented when apply/retry start a new run. */
    public long attemptId() {
        return attemptId;
    }

    /** The stage a CANCELLED analysis stopped in, or null. */
    public AnalysisStage interruptedStage() {
        return interruptedStage;
    }

    public String stageMessage() {
        return stageMessage;
    }

    /** The date the user applied to the job, or null when not applied (docs/API.md §4). */
    public LocalDate appliedAt() {
        return appliedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Integer score() {
        return score;
    }

    public Evaluation evaluation() {
        return evaluation;
    }

    public List<AnalysisSuggestion> suggestions() {
        return suggestions;
    }

    public List<CvChange> appliedChanges() {
        return appliedChanges;
    }

    /** The rewrite document of the last attempt, kept to resume PDF generation after a cancel. */
    public CvDocument rewrittenDocument() {
        return rewrittenDocument;
    }

    public Path resultFile() {
        return resultFile;
    }

    public String errorCode() {
        return errorCode;
    }

    public String errorMessage() {
        return errorMessage;
    }

    private static List<AnalysisSuggestion> assignIds(List<Suggestion> raw) {
        List<AnalysisSuggestion> result = new ArrayList<>();
        if (raw != null) {
            int index = 1;
            for (Suggestion suggestion : raw) {
                if (suggestion == null) continue;
                result.add(new AnalysisSuggestion("s" + index++, suggestion.section(), suggestion.problem(),
                        suggestion.proposal(), suggestion.before(), suggestion.after(), suggestion.priority()));
            }
        }
        return List.copyOf(result);
    }

    private static String joinNonBlank(String sep, String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) continue;
            if (!builder.isEmpty()) builder.append(sep);
            builder.append(part.strip());
        }
        return builder.toString();
    }
}
