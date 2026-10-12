package pl.example.cvchecker.api;

import java.time.Instant;

import pl.example.cvchecker.job.Stage;

/**
 * State snapshot of a job (docs/API.md §2): lets the UI restore itself after a page refresh.
 * {@code resultUrl} and {@code report} are present only when the job is COMPLETED; the error fields
 * only when it is FAILED.
 */
public record JobSnapshot(String jobId, String fileName, Stage stage, Instant createdAt, Integer progress,
        String resultUrl, Object report, String errorCode, String errorMessage, String model) {
}
