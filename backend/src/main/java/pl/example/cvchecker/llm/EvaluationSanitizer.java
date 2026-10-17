package pl.example.cvchecker.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Post-processes a model evaluation (prompts/analyze.st Steps 3-5) so the report stays consistent
 * with the CV no matter what the model returned. Runs after the structural validation and before the
 * evaluation is stored; every rule is deterministic and independent of the model:
 *
 * <ol>
 *   <li>recomputes {@code matchScore} from {@code requirements} with the prompt's fixed formula;</li>
 *   <li>moves a missing keyword back to matched when the CV does contain it;</li>
 *   <li>drops a suggestion whose {@code before} is not found in the CV;</li>
 *   <li>drops a suggestion whose {@code before}/{@code after} are the same ignoring punctuation,
 *       whitespace and case;</li>
 *   <li>flags (via {@link Suggestion#needsConfirmation()}) a suggestion whose {@code after} mentions
 *       words the CV does not contain.</li>
 * </ol>
 */
final class EvaluationSanitizer {

    /** A missing MUST requirement caps the score at this value (prompts/analyze.st Step 3). */
    static final int MISSING_MUST_CAP = 50;

    /** Tier weights from the prompt: must-have 70, nice-to-have 20, soft 10. */
    private static final int MUST_WEIGHT = 70;
    private static final int NICE_WEIGHT = 20;
    private static final int SOFT_WEIGHT = 10;

    private EvaluationSanitizer() {
    }

    /** Returns a corrected copy of {@code evaluation}; a {@code null} input stays {@code null}. */
    static Evaluation sanitize(Evaluation evaluation, String cvText) {
        if (evaluation == null) {
            return null;
        }
        String cv = cvText == null ? "" : cvText;
        List<Requirement> requirements = evaluation.requirements() == null ? List.of() : evaluation.requirements();

        int score = recomputeScore(requirements, evaluation.matchScore());
        List<String> matched = new ArrayList<>(nullSafe(evaluation.matchedKeywords()));
        List<String> missing = new ArrayList<>();
        for (String keyword : nullSafe(evaluation.missingKeywords())) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            if (containsIgnoreCase(cv, keyword)) {
                if (!containsIgnoreCase(matched, keyword)) {
                    matched.add(keyword);
                }
            } else {
                missing.add(keyword);
            }
        }
        List<Suggestion> suggestions = sanitizeSuggestions(evaluation.suggestions(), cv);

        return new Evaluation(evaluation.employer(), evaluation.position(), requirements, score,
                List.copyOf(matched), List.copyOf(missing), suggestions, evaluation.summary(),
                evaluation.shortDescription());
    }

    /**
     * Derives the score from the requirement statuses with the prompt's formula, renormalising the
     * weights over the tiers that actually have items. Falls back to the model's score (clamped) when
     * there are no requirements to score.
     */
    static int recomputeScore(List<Requirement> requirements, int fallback) {
        if (requirements == null || requirements.isEmpty()) {
            return clamp(fallback);
        }
        double mustSum = 0;
        double niceSum = 0;
        double softSum = 0;
        int mustCount = 0;
        int niceCount = 0;
        int softCount = 0;
        boolean missingMust = false;
        for (Requirement requirement : requirements) {
            if (requirement == null || requirement.type() == null) {
                continue;
            }
            RequirementStatus status = requirement.status() == null ? RequirementStatus.MISSING : requirement.status();
            double value = switch (status) {
                case MET -> 1.0;
                case PARTIAL -> 0.5;
                case MISSING -> 0.0;
            };
            switch (requirement.type()) {
                case MUST -> {
                    mustSum += value;
                    mustCount++;
                    missingMust |= status == RequirementStatus.MISSING;
                }
                case NICE -> {
                    niceSum += value;
                    niceCount++;
                }
                case SOFT -> {
                    softSum += value;
                    softCount++;
                }
            }
        }
        int totalWeight = (mustCount > 0 ? MUST_WEIGHT : 0) + (niceCount > 0 ? NICE_WEIGHT : 0)
                + (softCount > 0 ? SOFT_WEIGHT : 0);
        if (totalWeight == 0) {
            return clamp(fallback);
        }
        double weighted = (mustCount > 0 ? MUST_WEIGHT * (mustSum / mustCount) : 0)
                + (niceCount > 0 ? NICE_WEIGHT * (niceSum / niceCount) : 0)
                + (softCount > 0 ? SOFT_WEIGHT * (softSum / softCount) : 0);
        int score = (int) Math.round(weighted / totalWeight * 100.0);
        if (missingMust) {
            score = Math.min(score, MISSING_MUST_CAP);
        }
        return clamp(score);
    }

    private static List<Suggestion> sanitizeSuggestions(List<Suggestion> raw, String cv) {
        if (raw == null) {
            return List.of();
        }
        String normalizedCv = normalize(cv);
        List<Suggestion> result = new ArrayList<>();
        for (Suggestion suggestion : raw) {
            if (suggestion == null) {
                continue;
            }
            String before = suggestion.before() == null ? "" : suggestion.before();
            String after = suggestion.after() == null ? "" : suggestion.after();
            if (!before.isBlank() && !normalizedCv.contains(normalize(before))) {
                continue;
            }
            if (!before.isBlank() && !after.isBlank() && sameIgnoringFormatting(before, after)) {
                continue;
            }
            boolean needsConfirmation = hasWordsNotInCv(after, cv);
            result.add(new Suggestion(suggestion.section(), suggestion.problem(), suggestion.proposal(),
                    before, after, suggestion.priority(), needsConfirmation));
        }
        return List.copyOf(result);
    }

    /** True when {@code after} contains a word (2+ characters) that does not appear in the CV. */
    private static boolean hasWordsNotInCv(String after, String cv) {
        if (after == null || after.isBlank()) {
            return false;
        }
        for (String word : after.split("[^\\p{L}\\p{N}]+")) {
            if (word.length() < 2) {
                continue;
            }
            if (!containsIgnoreCase(cv, word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameIgnoringFormatting(String before, String after) {
        return normalize(before).replaceAll("[^\\p{L}\\p{N}]", "")
                .equals(normalize(after).replaceAll("[^\\p{L}\\p{N}]", ""));
    }

    /** Lower-cases and collapses every whitespace run to a single space (for substring search). */
    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static boolean containsIgnoreCase(List<String> haystack, String needle) {
        return haystack.stream().anyMatch(existing -> existing != null
                && existing.equalsIgnoreCase(needle));
    }

    private static List<String> nullSafe(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }
}
