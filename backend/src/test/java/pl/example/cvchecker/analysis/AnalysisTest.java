package pl.example.cvchecker.analysis;

import java.time.Instant;
import java.util.List;

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
                "before", "after", priority);
    }

    private static Analysis awaitingReview() {
        Analysis analysis = new Analysis("a1", "cv.pdf", "CV text", "posting", "qwen", Instant.parse("2026-10-12T10:00:00Z"));
        analysis.setEvaluation(new Evaluation("Acme", "Senior Java Developer", 58,
                List.of("Java"), List.of("Kubernetes"), List.of(suggestion("Skills", Priority.HIGH)), "Fine."));
        analysis.transition(AnalysisStage.AWAITING_REVIEW, 40);
        return analysis;
    }

    @Test
    void suggestionIdsAreIssuedByTheBackend() {
        Analysis analysis = new Analysis("a1", "cv.pdf", "CV text", "posting", "qwen");
        analysis.setEvaluation(new Evaluation("Acme", "Dev", 50, List.of(), List.of(),
                List.of(suggestion("Skills", Priority.HIGH), suggestion("Summary", Priority.LOW)), "ok"));

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
        analysis.setEvaluation(new Evaluation("Initech", "Full Stack", 80,
                List.of(), List.of(), List.of(suggestion("Skills", Priority.HIGH)), "ok"));

        assertThat(analysis.title()).isEqualTo("Interview at Globex");
    }

    @Test
    void applyReturnsOnlyTheApprovedSuggestions() {
        Analysis analysis = new Analysis("a1", "cv.pdf", "CV text", "posting", "qwen");
        analysis.setEvaluation(new Evaluation("Acme", "Dev", 50, List.of(), List.of(),
                List.of(suggestion("Skills", Priority.HIGH), suggestion("Summary", Priority.MEDIUM)), "ok"));
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
}
