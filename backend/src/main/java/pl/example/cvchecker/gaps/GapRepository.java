package pl.example.cvchecker.gaps;

import java.util.List;
import java.util.Optional;

/**
 * Skill-gap storage (docs/API.md §9). The default implementation is SQLite-backed
 * ({@code SqliteGapRepository}) so curated gaps survive restarts; {@code cvchecker.persistence=memory}
 * selects the volatile in-memory variant used by tests (mirrors the analysis registry seam).
 */
public interface GapRepository {

    List<SkillGap> findAll();

    Optional<SkillGap> find(String id);

    /** Inserts a new gap or replaces the existing row with the same id. */
    void save(SkillGap gap);

    /** Hard-deletes a gap. False when the id is unknown. */
    boolean delete(String id);
}
