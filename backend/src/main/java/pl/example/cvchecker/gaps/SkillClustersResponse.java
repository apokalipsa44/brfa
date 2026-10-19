package pl.example.cvchecker.gaps;

import java.time.Instant;
import java.util.List;

/**
 * Payload of the grouped-skills endpoints (docs/API.md §9): the current clusters plus the background
 * job's state. {@code lastError} is null when the last run succeeded.
 */
public record SkillClustersResponse(boolean running, Instant lastRunAt, String lastError,
        List<GroupedSkill> clusters) {
}
