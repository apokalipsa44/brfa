package pl.example.cvchecker.gaps;

import java.time.Instant;
import java.util.List;

/**
 * One skill gap as returned by {@code GET /api/skill-gaps} (docs/API.md §9). {@code occurrences} and
 * {@code sources} are computed live from the analyses on every read; {@code origin} is
 * {@code DERIVED} for a gap aggregated from analyses and {@code MANUAL} for a user-created one.
 */
public record GapItem(String id, String text, GapKind kind, GapStatus status, String note, String origin,
        int occurrences, List<GapSource> sources, Instant createdAt, Instant updatedAt) {

    /** One analysis that raised a gap: its id and its (possibly auto-generated) title. */
    public record GapSource(String analysisId, String title) {
    }
}
