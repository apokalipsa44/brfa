package pl.example.cvchecker.analysis;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import pl.example.cvchecker.llm.CvChange;
import pl.example.cvchecker.llm.Evaluation;
import pl.example.cvchecker.llm.Priority;
import pl.example.cvchecker.llm.Suggestion;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Analysis state rules (docs/API.md §4/§6.2): ids are issued on the backend, the auto-title follows
 * the evaluation until the user renames it, and retry remembers the failed stage.
 */
class AnalysisTest {

    private static Suggestion suggestion(String section, Priority priority) {
        return new Suggestion(section, "problem " + section, "proposal " + section,
                "before", "after", priority, false);
    }

    private static Analysis awaitingReview() {
        Analysis analysis = new Analysis("a1", "cv.pdf", "CV text", "posting", "qwen", Instant.parse("2026-10-12T10:00:00Z"));
        analysis.setEvaluation(new Evaluation("Acme", "Senior Java Developer", List.of(), 58,
                List.of("Java"), List.of("Kubernetes"), List.of(suggestion("Skills", Priority.HIGH)), "Fine.",
                "A backend role."));
        analysis.transition(AnalysisStage.AWAITING_REVIEW, 40);
        return analysis;
    }

    @Test
    void suggestionIdsAreIssuedByTheBackend() {
        Analysis analysis = new Analysis("a1", "cv.pdf", "CV text", "posting", "qwen");
        analysis.setEvaluation(new Evaluation("Acme", "Dev", List.of(), 50, List.of(), List.of(),
                List.of(suggestion("Skills", Priority.HIGH), suggestion("Summary", Priority.LOW)), "ok", null));

        assertThat(analysis.suggestions()).extracting(AnalysisSuggestion::id).containsExactly("s1", "s2");
    }

    @Test
    void autoTitleComesFromEmployerAndPosition() {
        assertThat(awaitingReview().title()).isEqualTo("Acme · Senior Java Developer");
    }

    @Test
    void autoTitleFallsBackToAFirstUntitledAnalysis() {
        Analysis analysis = new Analysis("a1", "cv.pdf", "CV text", "posting", "qwen",
                Instant.parse("2026-10-12T10:00:00Z"));

        assertThat(analysis.title()).isEqualTo("Untitled analysis · 12 Oct 2026");
    }

    /** A user rename must survive later evaluation-ish updates (docs/API.md §6.2). */
    @Test
    void userRenameWinsOverTheAutoTitle() {
        Analysis analysis = awaitingReview();
        analysis.rename("Interview at Globex");
        // A re-evaluation (e.g. after a retry) must not overwrite the user's title.
        analysis.setEvaluation(new Evaluation("Initech", "Full Stack", List.of(), 80,
                List.of(), List.of(), List.of(suggestion("Skills", Priority.HIGH)), "ok", null));

        assertThat(analysis.title()).isEqualTo("Interview at Globex");
    }

    @Test
    void applyReturnsOnlyTheApprovedSuggestions() {
        Analysis analysis = new Analysis("a1", "cv.pdf", "CV text", "posting", "qwen");
        analysis.setEvaluation(new Evaluation("Acme", "Dev", List.of(), 50, List.of(), List.of(),
                List.of(suggestion("Skills", Priority.HIGH), suggestion("Summary", Priority.MEDIUM)), "ok", null));
        analysis.transition(AnalysisStage.AWAITING_REVIEW, 40);

        analysis.beginRewrite(List.of("s2"));

        assertThat(analysis.approvedSuggestions()).extracting(Suggestion::section).containsExactly("Summary");
        assertThat(analysis.stage()).isEqualTo(AnalysisStage.REWRITING);
    }

    @Test
    void retryResumesAtTheFailedStage() {
        Analysis analysis = new Analysis("a1", "cv.pdf", "CV text", "posting", "qwen");
        analysis.fail("LLM_INVALID_OUTPUT", "bad json", AnalysisStage.REWRITING);

        analysis.resetForRetry();

        assertThat(analysis.stage()).isEqualTo(AnalysisStage.REWRITING);
        assertThat(analysis.errorCode()).isNull();
    }

    @Test
    void appliedChangesAreRetainedPerAnalysis() {
        Analysis analysis = awaitingReview();
        analysis.setAppliedChanges(List.of(new CvChange("Skills", "Docker", "Docker, Kubernetes", "Approved")));

        assertThat(analysis.appliedChanges()).hasSize(1);
        assertThat(analysis.appliedChanges().get(0).section()).isEqualTo("Skills");
    }

    /** Cancelling a cancellable stage records where it stopped so retry can resume there (§1.1). */
    @Test
    void cancelRemembersInterruptedStageForRetry() {
        Analysis analysis = new Analysis("a1", "cv.pdf", "CV text", "posting", "qwen");
        analysis.transition(AnalysisStage.EVALUATING, 15);

        assertThat(analysis.cancel()).isTrue();
        assertThat(analysis.stage()).isEqualTo(AnalysisStage.CANCELLED);
        assertThat(analysis.interruptedStage()).isEqualTo(AnalysisStage.EVALUATING);

        assertThat(analysis.resetForRetry()).isTrue();
        assertThat(analysis.stage()).isEqualTo(AnalysisStage.EVALUATING);
        assertThat(analysis.interruptedStage()).isNull();
    }

    /** AWAITING_REVIEW is not cancellable: the analysis is waiting on the user, not on a worker. */
    @Test
    void cancelIsRejectedWhenTheStageIsNotCancellable() {
        Analysis analysis = awaitingReview();

        assertThat(analysis.cancel()).isFalse();
        assertThat(analysis.stage()).isEqualTo(AnalysisStage.AWAITING_REVIEW);
    }

    /** A cancelled run that finishes late must not overwrite the CANCELLED state (§1.1). */
    @Test
    void aCancelledRunCannotWriteState() {
        Analysis analysis = new Analysis("a1", "cv.pdf", "CV text", "posting", "qwen");
        long token = analysis.attemptId();
        analysis.transition(AnalysisStage.EVALUATING, 15);
        analysis.cancel();

        assertThat(analysis.attemptTransition(token, AnalysisStage.AWAITING_REVIEW, 40)).isFalse();
        assertThat(analysis.attemptFail(token, "INTERNAL_ERROR", "boom", AnalysisStage.EVALUATING)).isFalse();
        assertThat(analysis.stage()).isEqualTo(AnalysisStage.CANCELLED);
        assertThat(analysis.errorCode()).isNull();
    }

    /** Starting a new run invalidates the previous attempt's writes (attemptId guard, §1.1). */
    @Test
    void aStaleAttemptCannotOverwriteANewRun() {
        Analysis analysis = awaitingReview();
        long previous = analysis.attemptId();

        analysis.beginRewrite(List.of("s1")); // increments attemptId

        assertThat(analysis.attemptId()).isEqualTo(previous + 1);
        assertThat(analysis.attemptTransition(previous, AnalysisStage.COMPLETED, 100)).isFalse();
        assertThat(analysis.stage()).isEqualTo(AnalysisStage.REWRITING);
    }

    /** A link analysis has no posting text until FETCHING_POSTING resolves it (§4.1). */
    @Test
    void linkAnalysisResolvesPostingTextAndProvenance() {
        Analysis analysis = Analysis.fromUrl("a1", "cv.pdf", "CV text", "https://example.com/job", "qwen");

        assertThat(analysis.sourceUrl()).isEqualTo("https://example.com/job");
        assertThat(analysis.postingText()).isNull();
        assertThat(analysis.postingSource()).isNull();

        long token = analysis.attemptId();
        analysis.attemptSetPosting(token, "Extracted posting", PostingSource.STATIC_HTML, false, "Acme", "Dev");

        assertThat(analysis.postingText()).isEqualTo("Extracted posting");
        assertThat(analysis.postingSource()).isEqualTo(PostingSource.STATIC_HTML);
        assertThat(analysis.title()).isEqualTo("Acme · Dev"); // from the structured-data fallback (§6.2)
    }

    /** A stale attempt must not clobber the posting provenance either. */
    @Test
    void staleAttemptCannotOverwriteThePosting() {
        Analysis analysis = Analysis.fromUrl("a1", "cv.pdf", "CV text", "https://example.com/job", "qwen");
        long previous = analysis.attemptId();
        analysis.cancel();
        analysis.resetForRetry(); // a new run starts; the old token is stale

        assertThat(analysis.attemptSetPosting(previous, "late", PostingSource.JSON_LD, false, null, null)).isFalse();
        assertThat(analysis.postingText()).isNull();
    }

    @Test
    void shortDescriptionComesFromTheEvaluation() {
        assertThat(awaitingReview().shortDescription()).isEqualTo("A backend role.");
        assertThat(new Analysis("a1", "cv.pdf", "text", "posting", "qwen").shortDescription()).isNull();
    }

    @Test
    void markAppliedKeepsAnExistingDate() {
        Analysis analysis = new Analysis("a1", "cv.pdf", "text", "posting", "qwen");
        analysis.setApplied(LocalDate.of(2026, 10, 1));

        analysis.markApplied();

        assertThat(analysis.appliedAt()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    void clearAppliedRemovesTheDate() {
        Analysis analysis = new Analysis("a1", "cv.pdf", "text", "posting", "qwen");
        analysis.setApplied(LocalDate.of(2026, 10, 1));

        analysis.clearApplied();

        assertThat(analysis.appliedAt()).isNull();
    }

    /** User-set values (title, applied date) survive retry/re-evaluation (docs/API.md §4). */
    @Test
    void appliedDateAndUserTitleSurviveRetry() {
        Analysis analysis = new Analysis("a1", "cv.pdf", "text", "posting", "qwen");
        analysis.rename("My title");
        analysis.setApplied(LocalDate.of(2026, 10, 1));
        analysis.transition(AnalysisStage.EVALUATING, 15);
        analysis.fail("LLM_INVALID_OUTPUT", "boom", AnalysisStage.EVALUATING);

        analysis.resetForRetry();
        analysis.setEvaluation(new Evaluation("Acme", "Dev", List.of(), 70, List.of(), List.of(),
                List.of(suggestion("Skills", Priority.HIGH)), "ok", "role"));
        analysis.transition(AnalysisStage.AWAITING_REVIEW, 40);

        assertThat(analysis.appliedAt()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(analysis.title()).isEqualTo("My title");
    }

    /** Applied changes notify the registry's listener so an analysis.updated event is broadcast. */
    @Test
    void appliedChangesNotifyTheListener() {
        Analysis analysis = new Analysis("a1", "cv.pdf", "text", "posting", "qwen");
        AtomicInteger changes = new AtomicInteger();
        analysis.setChangeListener(changes::incrementAndGet);

        analysis.markApplied();
        analysis.clearApplied();

        assertThat(changes.get()).isEqualTo(2);
    }

    /** A full re-run drops every stage result and returns to QUEUED, keeping user values (§4). */
    @Test
    void rerunResetsResultsAndKeepsUserValues() {
        Analysis analysis = Analysis.fromUrl("a1", "cv.pdf", "CV text", "https://example.com/job", "qwen");
        long token = analysis.attemptId();
        analysis.attemptSetPosting(token, "Extracted posting", PostingSource.STATIC_HTML, false, "Acme", "Dev");
        analysis.setEvaluation(new Evaluation("Acme", "Dev", List.of(), 58, List.of("Java"), List.of("K8s"),
                List.of(suggestion("Skills", Priority.HIGH)), "Fine.", "A backend role."));
        analysis.rename("My title");
        analysis.setApplied(LocalDate.of(2026, 10, 1));
        analysis.transition(AnalysisStage.AWAITING_REVIEW, 40);

        assertThat(analysis.resetForRerun()).isTrue();

        assertThat(analysis.stage()).isEqualTo(AnalysisStage.QUEUED);
        assertThat(analysis.progress()).isZero();
        assertThat(analysis.score()).isNull();
        assertThat(analysis.evaluation()).isNull();
        assertThat(analysis.suggestions()).isEmpty();
        assertThat(analysis.postingText()).isNull();
        assertThat(analysis.title()).isEqualTo("My title");
        assertThat(analysis.appliedAt()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(analysis.attemptId()).isEqualTo(token + 1);
    }

    /** A pasted analysis cannot be re-run from its link (there is none); a running one must be cancelled first. */
    @Test
    void rerunIsRejectedForPastedOrRunningAnalyses() {
        Analysis pasted = new Analysis("a1", "cv.pdf", "CV text", "posting", "qwen");
        pasted.transition(AnalysisStage.AWAITING_REVIEW, 40);
        assertThat(pasted.resetForRerun()).isFalse();

        Analysis running = Analysis.fromUrl("a2", "cv.pdf", "CV text", "https://example.com/job", "qwen");
        running.transition(AnalysisStage.EVALUATING, 15);
        assertThat(running.resetForRerun()).isFalse();
    }
}
