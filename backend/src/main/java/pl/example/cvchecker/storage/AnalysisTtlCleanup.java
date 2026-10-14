package pl.example.cvchecker.storage;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import pl.example.cvchecker.analysis.Analysis;
import pl.example.cvchecker.analysis.AnalysisRegistry;
import pl.example.cvchecker.analysis.AnalysisStage;

/**
 * Removes analyses (and their result PDFs) older than the configured TTL (docs/API.md §8). Only
 * terminal analyses are eligible, so a long running analysis is never deleted out from under the
 * user. Removing one broadcasts {@code analysis.deleted}, so every open tab drops its card.
 */
@Component
public class AnalysisTtlCleanup {

    private static final Logger log = LoggerFactory.getLogger(AnalysisTtlCleanup.class);

    private final AnalysisRegistry registry;
    private final Duration ttl;

    public AnalysisTtlCleanup(AnalysisRegistry registry, @Value("${cvchecker.analyses.ttl:24h}") Duration ttl) {
        this.registry = registry;
        this.ttl = ttl;
    }

    @Scheduled(fixedDelayString = "${cvchecker.analyses.ttl-check:1h}")
    public void cleanup() {
        Instant cutoff = Instant.now().minus(ttl);
        List<String> expired = registry.findAll().stream()
                .filter(Analysis::isTerminal)
                .filter(a -> a.createdAt().isBefore(cutoff))
                .map(Analysis::id)
                .toList();

        for (String id : expired) {
            registry.find(id).ifPresent(this::delete);
        }
        if (!expired.isEmpty()) {
            log.info("Removed {} analyses older than {}", expired.size(), ttl);
        }
    }

    private void delete(Analysis analysis) {
        if (registry.remove(analysis.id()) && analysis.resultFile() != null) {
            try {
                Files.deleteIfExists(analysis.resultFile());
            } catch (Exception e) {
                log.warn("Could not delete result file for analysis {}: {}", analysis.id(), e.getMessage());
            }
        }
    }

    /** Stages a terminal analysis is eligible for cleanup. */
    static boolean isEligible(AnalysisStage stage) {
        return stage == AnalysisStage.COMPLETED || stage == AnalysisStage.FAILED
                || stage == AnalysisStage.CANCELLED;
    }
}
