package pl.example.cvchecker.analysis;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import pl.example.cvchecker.api.AnalysisSummary;
import pl.example.cvchecker.llm.CvChange;
import pl.example.cvchecker.llm.Evaluation;
import pl.example.cvchecker.llm.Suggestion;

/**
 * In-memory state of one analysis (docs/API.md §4). Thread-safe: stage transitions happen under
 * {@link #eventLock}; {@code changeListener} fires after every change so the registry can broadcast
 * {@code analysis.updated} events. The analysis snapshots the CV text and the active model id at
 * creation, so replacing the CV or switching the model never disturbs an existing analysis.
 */
public class Analysis {

    /** Title fallback before evaluation finishes (docs/API.md §6.2). */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** Single lock guarding stage transitions so events fire exactly once per change. */
    public final Object eventLock = new Object();

    private final String id;
    private final String cvFileName;
    private final String cvText;
    private final String jobPosting;
    private final String modelId;
    private final Instant createdAt;

    private volatile AnalysisStage stage = AnalysisStage.QUEUED;
    private volatile int progress = 0;
    private volatile Instant updatedAt;
    private volatile String title;
    private volatile boolean userEditedTitle;
    private volatile Integer score;
    private volatile Evaluation evaluation;
    private volatile List<AnalysisSuggestion> suggestions = List.of();
    private volatile List<String> approvedSuggestionIds;
    private volatile List<CvChange> appliedChanges;
    private volatile Path resultFile;
    private volatile String errorCode;
    private volatile String errorMessage;
    private volatile AnalysisStage failedStage;

    /** Notified after every change so the registry can publish analysis.updated events. */
    private volatile Runnable changeListener;

    public Analysis(String id, String cvFileName, String cvText, String jobPosting, String modelId) {
        this(id, cvFileName, cvText, jobPosting, modelId, Instant.now());
    }

    Analysis(String id, String cvFileName, String cvText, String jobPosting, String modelId, Instant createdAt) {
        this.id = id;
        this.cvFileName = cvFileName;
        this.cvText = cvText;
        this.jobPosting = jobPosting;
        this.modelId = modelId;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    void setChangeListener(Runnable changeListener) {
        this.changeListener = changeListener;
    }

    private void notifyChanged() {
        Runnable listener = this.changeListener;
        if (listener != null) listener.run();
    }

    /** Moves the analysis to {@code newStage} with the stage's indicative progress (§1.1). */
    public void transition(AnalysisStage newStage, int newProgress) {
        synchronized (eventLock) {
            this.stage = newStage;
            this.progress = newProgress;
            this.updatedAt = Instant.now();
        }
        notifyChanged();
    }

    /** Marks the analysis FAILED; {@code failedStage} remembers what to re-run on retry. */
    public void fail(String code, String message, AnalysisStage failedStage) {
        synchronized (eventLock) {
            this.stage = AnalysisStage.FAILED;
            this.errorCode = code;
            this.errorMessage = message;
            this.failedStage = failedStage;
            this.updatedAt = Instant.now();
        }
        notifyChanged();
    }

    /** Stores the evaluation, scores it and assigns stable ids (s1..sN) to its suggestions. */
    public void setEvaluation(Evaluation evaluation) {
        synchronized (eventLock) {
            this.evaluation = evaluation;
            this.score = evaluation.matchScore();
            this.suggestions = assignIds(evaluation.suggestions());
            this.updatedAt = Instant.now();
        }
        notifyChanged();
    }

    /** Approves suggestions and starts stage 2 (called synchronously by {@code /apply}, §4). */
    public void beginRewrite(List<String> approvedIds) {
        synchronized (eventLock) {
            this.approvedSuggestionIds = List.copyOf(approvedIds);
            this.stage = AnalysisStage.REWRITING;
            this.progress = 65;
            this.updatedAt = Instant.now();
        }
        notifyChanged();
    }

    public void setAppliedChanges(List<CvChange> changes) {
        this.appliedChanges = changes;
    }

    public void setResultFile(Path resultFile) {
        this.resultFile = resultFile;
    }

    public void complete() {
        transition(AnalysisStage.COMPLETED, 100);
    }

    /** Moves a FAILED analysis back to the failed stage so retry can re-run it (§4). */
    public void resetForRetry() {
        synchronized (eventLock) {
            AnalysisStage target = failedStage == null ? AnalysisStage.EVALUATING : failedStage;
            this.stage = target;
            this.progress = target == AnalysisStage.REWRITING ? 65 : 15;
            this.errorCode = null;
            this.errorMessage = null;
            this.updatedAt = Instant.now();
        }
        notifyChanged();
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

    public AnalysisSummary summary() {
        return new AnalysisSummary(id, title(), stage, score, progress, createdAt, updatedAt, errorCode, errorMessage);
    }

    /** Auto-generated "Employer · Position" once evaluation finishes, otherwise the date fallback. */
    public String title() {
        if (userEditedTitle && title != null) return title;
        if (evaluation != null) {
            String generated = joinNonBlank(" · ", evaluation.employer(), evaluation.position());
            if (!generated.isBlank()) return generated;
        }
        return "Untitled analysis · " + DATE.format(createdAt.atZone(ZoneId.systemDefault()));
    }

    /** True once the analysis reached COMPLETED or FAILED (terminal states, §1.1). */
    public boolean isTerminal() {
        return stage == AnalysisStage.COMPLETED || stage == AnalysisStage.FAILED;
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

    public String jobPosting() {
        return jobPosting;
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

    public List<String> approvedSuggestionIds() {
        return approvedSuggestionIds;
    }

    public List<CvChange> appliedChanges() {
        return appliedChanges;
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

    /** The stage that failed (EVALUATING/Rewriting); re-run on retry. */
    public AnalysisStage failedStage() {
        return failedStage;
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