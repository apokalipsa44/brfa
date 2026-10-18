package pl.example.cvchecker.gaps;

import java.time.Instant;

/**
 * One persisted skill gap (docs/API.md §9): a term aggregated from the analyses' missing
 * requirements and nice-to-haves, or a gap the user added by hand. The {@code key} is the
 * normalized text used to match a gap against every analysis; user fields ({@code text},
 * {@code status}, {@code note}) are never overwritten by re-aggregation. {@code manual} marks
 * user-created gaps, {@code dismissed} a soft-deleted derived gap that must not reappear on the
 * next sync. Records are immutable: the service rebuilds them on every change.
 */
public record SkillGap(String id, String key, String text, GapKind kind, GapStatus status, String note,
        boolean manual, boolean dismissed, Instant createdAt, Instant updatedAt) {
}
