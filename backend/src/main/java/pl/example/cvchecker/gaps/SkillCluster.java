package pl.example.cvchecker.gaps;

import java.time.Instant;
import java.util.List;

/**
 * One canonical skill produced by the background regrouping job (docs/API.md §9): a group of gap
 * phrasings (possibly Polish) folded under one English label, plus the model's importance score and
 * rationale. Immutable; the job recomputes the whole set on every run. {@code memberGapIds} points at
 * the curated {@code skill_gaps} rows, which are never modified by regrouping.
 */
public record SkillCluster(String id, String label, int importance, String rationale,
        List<String> memberGapIds, Instant createdAt, Instant updatedAt) {
}
