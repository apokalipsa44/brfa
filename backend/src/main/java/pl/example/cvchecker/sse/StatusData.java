package pl.example.cvchecker.sse;

import pl.example.cvchecker.job.Stage;

/** Payload of a {@code status} SSE event (docs/API.md). */
public record StatusData(String jobId, Stage stage, String message, Integer progress) {}
