package pl.example.cvchecker.sse;

import pl.example.cvchecker.job.Stage;

/**
 * Payload of the terminal {@code completed} SSE event (docs/API.md). {@code report} is null while the
 * simulated pipeline runs; the real pipeline fills it from M7+ (analysis + changes list).
 */
public record CompletedData(String jobId, Stage stage, int progress, String resultUrl, Object report) {}
