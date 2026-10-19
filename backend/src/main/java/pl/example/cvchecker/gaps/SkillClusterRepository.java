package pl.example.cvchecker.gaps;

import java.util.List;

/**
 * Grouped-skill storage (docs/API.md §9). The default implementation is SQLite-backed
 * ({@code SqliteSkillClusterRepository}) so clusters survive restarts;
 * {@code cvchecker.persistence=memory} selects the volatile in-memory variant (mirrors the gap seam).
 */
public interface SkillClusterRepository {

    List<SkillCluster> findAll();

    /** Replaces the whole cluster set; the regrouping job recomputes it from scratch. */
    void replaceAll(List<SkillCluster> clusters);
}
