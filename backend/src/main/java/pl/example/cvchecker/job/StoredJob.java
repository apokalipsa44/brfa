package pl.example.cvchecker.job;

import java.time.Instant;

/**
 * Persisted projection of a {@link CvJob} (docs/API.md §8): the fields needed to rebuild the job
 * list after a backend restart. Deliberately small — no CV/posting text and no temp paths, which
 * are personal data / invalid after a restart (AGENTS.md).
 */
public record StoredJob(
        String jobId,
        String fileName,
        Stage stage,
        Instant createdAt,
        Integer progress,
        String model,
        String errorCode,
        String errorMessage,
        JobReport report) {
}
