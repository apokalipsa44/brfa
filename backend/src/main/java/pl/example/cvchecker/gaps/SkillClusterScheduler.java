package pl.example.cvchecker.gaps;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import pl.example.cvchecker.analysis.AnalysisStage;
import pl.example.cvchecker.analysis.AnalysisUpdatedEvent;

/**
 * Triggers the background regrouping run (docs/API.md §9) after every
 * {@code cvchecker.skill-gaps.group-every} completed analyses. It only counts distinct completed
 * analysis ids and submits to the dedicated executor, so it never prolongs an analysis and never
 * blocks the thread publishing the event. The counter is in-memory: after a restart it simply takes
 * up to N more analyses to trigger again (the overview does not need to be up to date).
 */
@Component
public class SkillClusterScheduler {

    private static final Logger log = LoggerFactory.getLogger(SkillClusterScheduler.class);

    private final SkillClusterService clusters;
    private final int groupEvery;
    private final Set<String> counted = ConcurrentHashMap.newKeySet();
    private final AtomicInteger sinceLastGrouping = new AtomicInteger();

    public SkillClusterScheduler(SkillClusterService clusters,
            @Value("${cvchecker.skill-gaps.group-every:5}") int groupEvery) {
        this.clusters = clusters;
        this.groupEvery = Math.max(1, groupEvery);
    }

    @EventListener
    public void onAnalysisUpdated(AnalysisUpdatedEvent event) {
        if (event.summary() == null || event.summary().stage() != AnalysisStage.COMPLETED) return;
        if (!counted.add(event.summary().id())) return;
        if (sinceLastGrouping.incrementAndGet() < groupEvery) return;
        if (clusters.rebuildAsync()) {
            sinceLastGrouping.set(0);
            log.info("Scheduled skill regrouping after {} completed analyses", groupEvery);
        }
    }
}
