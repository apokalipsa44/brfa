package pl.example.cvchecker.analysis;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Pure in-memory analysis registry (docs/API.md §8): a {@link ConcurrentHashMap} with no persistence
 * — a backend restart empties it. Every analysis change (and its removal) is published as a Spring
 * application event; the global SSE stream (M4) listens to them and fans them out.
 */
@Component
public class InMemoryAnalysisRegistry implements AnalysisRegistry {

    private final Map<String, Analysis> analyses = new ConcurrentHashMap<>();
    private final ApplicationEventPublisher events;

    public InMemoryAnalysisRegistry(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    public void create(Analysis analysis) {
        if (analyses.putIfAbsent(analysis.id(), analysis) != null) {
            throw new IllegalStateException("analysis already exists: " + analysis.id());
        }
        analysis.setChangeListener(() -> events.publishEvent(new AnalysisUpdatedEvent(analysis.summary())));
        events.publishEvent(new AnalysisUpdatedEvent(analysis.summary())); // the initial QUEUED state
    }

    @Override
    public Optional<Analysis> find(String id) {
        return Optional.ofNullable(analyses.get(id));
    }

    @Override
    public boolean remove(String id) {
        boolean removed = analyses.remove(id) != null;
        if (removed) {
            events.publishEvent(new AnalysisDeletedEvent(id));
        }
        return removed;
    }

    @Override
    public Collection<Analysis> findAll() {
        return analyses.values();
    }
}