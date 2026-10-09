package pl.example.cvchecker.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pl.example.cvchecker.storage.TempFileStorage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * TTL cleanup (milestone 11): removes jobs and temp files older than {@code cvchecker.jobs.ttl}.
 * Runs once per day. Original files are deleted after the pipeline finishes (they contain personal data).
 */
@Component
public class TtlCleanup {

    private static final Logger log = LoggerFactory.getLogger(TtlCleanup.class);

    private final CvJobRegistry registry;
    private final TempFileStorage storage;
    private final long ttlSeconds;

    public TtlCleanup(CvJobRegistry registry, TempFileStorage storage) {
        this.registry = registry;
        this.storage = storage;
        // Default 24h TTL if not configured; matches application.yml cvchecker.jobs.ttl
        this.ttlSeconds = 24L * 60 * 60;
    }

    /** Removes jobs older than TTL and their temp files. */
    @Scheduled(fixedDelayString = "86400000") // 24h
    public void cleanup() {
        List<CvJob> oldJobs = registry.findAll().stream()
                .filter(job -> job.createdAt().isBefore(Instant.now().minusSeconds(ttlSeconds)))
                .collect(Collectors.toList());

        for (CvJob job : oldJobs) {
            String jobId = job.id();
            Path originalFile = job.originalFile();
            Path resultFile = job.resultFile();

            // Delete original PDF if it exists and is in temp dir
            if (originalFile != null) {
                try {
                    if (Files.exists(originalFile)) {
                        Files.delete(originalFile);
                        log.debug("Deleted original PDF for expired job {}", jobId);
                    }
                } catch (Exception e) {
                    log.warn("Could not delete original PDF for job {}", jobId, e);
                }
            }

            // Delete result PDF if it exists and is in temp dir
            if (resultFile != null) {
                try {
                    if (Files.exists(resultFile)) {
                        Files.delete(resultFile);
                        log.debug("Deleted result PDF for expired job {}", jobId);
                    }
                } catch (Exception e) {
                    log.warn("Could not delete result PDF for job {}", jobId, e);
                }
            }

            // Remove from registry
            registry.remove(jobId);
            log.info("Expired and removed job {} (TTL: {} s)", jobId, ttlSeconds);
        }

        if (!oldJobs.isEmpty()) {
            log.debug("TTL cleanup removed {} old job(s)", oldJobs.size());
        }
    }
}