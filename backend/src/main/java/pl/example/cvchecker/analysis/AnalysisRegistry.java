package pl.example.cvchecker.analysis;

import java.util.Collection;
import java.util.Optional;

/**
 * Analysis storage (docs/API.md §8). Analyses are registered and queried only through this
 * interface. The default implementation is SQLite-backed ({@code SqliteAnalysisRegistry}) so analyses
 * survive restarts; {@code cvchecker.persistence=memory} selects the volatile in-memory variant used
 * by tests.
 */
public interface AnalysisRegistry {

    /** Registers the analysis and starts broadcasting analysis.updated events for it. */
    void create(Analysis analysis);

    Optional<Analysis> find(String id);

    /** Removes the analysis and broadcasts an analysis.deleted event. False when the id is unknown. */
    boolean remove(String id);

    Collection<Analysis> findAll();
}