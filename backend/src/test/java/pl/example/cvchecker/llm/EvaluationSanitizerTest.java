package pl.example.cvchecker.llm;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic checks applied to a model evaluation (prompts/analyze.st "Checks in code"): the score
 * is derived from the requirements, keywords are repaired against the CV, and unsupported suggestions
 * are dropped or flagged.
 */
class EvaluationSanitizerTest {

    private static Requirement req(RequirementType type, RequirementStatus status) {
        return new Requirement("Java", type, status, status == RequirementStatus.MISSING ? "" : "Java");
    }

    @Test
    void recomputesScoreWithPromptWeights() {
        // MUST met (1.0), NICE missing (0.0), SOFT met (1.0) -> 70 + 0 + 10 = 80.
        int score = EvaluationSanitizer.recomputeScore(
                List.of(req(RequirementType.MUST, RequirementStatus.MET),
                        req(RequirementType.NICE, RequirementStatus.MISSING),
                        req(RequirementType.SOFT, RequirementStatus.MET)), 5);
        assertThat(score).isEqualTo(80);
    }

    @Test
    void renormalisesWeightsOverPresentTiers() {
        assertThat(EvaluationSanitizer.recomputeScore(
                List.of(req(RequirementType.MUST, RequirementStatus.MET)), 0)).isEqualTo(100);
        assertThat(EvaluationSanitizer.recomputeScore(
                List.of(req(RequirementType.MUST, RequirementStatus.PARTIAL)), 0)).isEqualTo(50);
    }

    @Test
    void missingMustRequirementCapsTheScore() {
        // MUST met (1.0) + MUST missing (0.0) + NICE met (1.0) -> (70*0.5 + 20)/90*100 = 61 -> 50.
        int score = EvaluationSanitizer.recomputeScore(
                List.of(req(RequirementType.MUST, RequirementStatus.MET),
                        req(RequirementType.MUST, RequirementStatus.MISSING),
                        req(RequirementType.NICE, RequirementStatus.MET)), 0);
        assertThat(score).isEqualTo(EvaluationSanitizer.MISSING_MUST_CAP);
    }

    @Test
    void fallsBackToTheModelScoreWhenThereAreNoRequirements() {
        assertThat(EvaluationSanitizer.recomputeScore(List.of(), 61)).isEqualTo(61);
        assertThat(EvaluationSanitizer.recomputeScore(List.of(), 150)).isEqualTo(100);
    }

    @Test
    void movesAKnownKeywordOutOfMissing() {
        Evaluation evaluation = new Evaluation("Acme", "Dev", List.of(), 40, List.of("Java"),
                List.of("Spring Boot", "Terraform"), List.of(), "s", null);

        Evaluation sanitized = EvaluationSanitizer.sanitize(evaluation, "I use Spring Boot daily.");

        assertThat(sanitized.matchedKeywords()).containsExactly("Java", "Spring Boot");
        assertThat(sanitized.missingKeywords()).containsExactly("Terraform");
    }

    @Test
    void dropsSuggestionsWhoseBeforeIsNotInTheCv() {
        Evaluation evaluation = new Evaluation("Acme", "Dev", List.of(), 40, List.of(), List.of(),
                List.of(new Suggestion("Skills", "p", "q", "Kubernetes", "Kubernetes, Helm",
                        Priority.HIGH, false)), "s", null);

        Evaluation sanitized = EvaluationSanitizer.sanitize(evaluation, "Babysitting experience only.");

        assertThat(sanitized.suggestions()).isEmpty();
    }

    @Test
    void dropsSuggestionsThatOnlyChangeFormatting() {
        Evaluation evaluation = new Evaluation("Acme", "Dev", List.of(), 40, List.of(), List.of(),
                List.of(new Suggestion("Skills", "p", "q", "Docker, CI/CD", "Docker CI/CD",
                        Priority.HIGH, false)), "s", null);

        Evaluation sanitized = EvaluationSanitizer.sanitize(evaluation, "Docker, CI/CD");

        assertThat(sanitized.suggestions()).isEmpty();
    }

    @Test
    void flagsSuggestionsThatIntroduceUnknownWords() {
        Evaluation evaluation = new Evaluation("Acme", "Dev", List.of(), 40, List.of(), List.of(),
                List.of(new Suggestion("Skills", "p", "q", "Docker", "Docker, Kubernetes",
                        Priority.HIGH, false)), "s", null);

        Evaluation sanitized = EvaluationSanitizer.sanitize(evaluation, "Docker");

        assertThat(sanitized.suggestions()).hasSize(1);
        assertThat(sanitized.suggestions().get(0).needsConfirmation()).isTrue();
    }

    @Test
    void keepsSuggestionsFullyGroundedInTheCv() {
        Evaluation evaluation = new Evaluation("Acme", "Dev", List.of(), 40, List.of(), List.of(),
                List.of(new Suggestion("Skills", "p", "q", "Docker", "Docker, Kubernetes",
                        Priority.HIGH, false)), "s", null);

        Evaluation sanitized = EvaluationSanitizer.sanitize(evaluation, "Docker and Kubernetes");

        assertThat(sanitized.suggestions()).hasSize(1);
        assertThat(sanitized.suggestions().get(0).needsConfirmation()).isFalse();
    }
}
