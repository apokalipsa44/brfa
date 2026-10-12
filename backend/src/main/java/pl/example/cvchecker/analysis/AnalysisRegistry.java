package pl.example.cvchecker.analysis;

import java.util.Collection;
import java.util.Optional;

/**
 * Analysis storage (docs/API.md §8). Analyses are registered and queried only through this
 * interface; the implementation is pure in-memory (empty after a restart) but a persistent store
 * could be swapped in behind it without touching callers.
 */
public interface AnalysisRegistry {

    /** Registers the analysis and starts broadcasting analysis.updated events for it. */
    void create(Analysis analysis);

    Optional<Analysis> find(String id);

    /** Removes the analysis and broadcasts an analysis.deleted event. False when the id is unknown. */
    boolean remove(String id);

    Collection<Analysis> findAll();
}