package pl.example.cvchecker.gaps;

import java.time.Instant;
import java.util.List;

/**
 * One grouped + ranked skill as returned by {@code GET /api/skill-gaps/clusters} (docs/API.md §9).
 * {@code occurrences} is the sum of the member gaps' live occurrences; {@code kind} is
 * {@code MISSING} when any member is missing, otherwise {@code NICE}.
 */
public record GroupedSkill(String id, String label, int importance, String rationale, int occurrences,
        GapKind kind, List<Member> members, Instant updatedAt) {

    /** One underlying gap phrasing (possibly Polish) folded into the cluster. */
    public record Member(String text, GapKind kind, int occurrences) {
    }
}
