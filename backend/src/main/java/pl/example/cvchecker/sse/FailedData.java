package pl.example.cvchecker.sse;

import pl.example.cvchecker.job.Stage;

/** Payload of the terminal {@code failed} SSE event (docs/API.md). */
public record FailedData(String jobId, Stage stage, String errorCode, String message) {}
