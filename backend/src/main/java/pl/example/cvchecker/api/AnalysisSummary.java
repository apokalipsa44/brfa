package pl.example.cvchecker.api;

import java.time.Instant;

import pl.example.cvchecker.analysis.AnalysisStage;

/**
 * Analysis summary (docs/API.md §4): the list entry, the {@code analysis.updated} SSE payload and the
 * PATCH/POST response body. {@code title} is auto-generated once evaluation finishes; a user edit
 * sticks. {@code score} is null until evaluation is done; the error fields only on FAILED.
 * {@code attemptId} is the run counter, {@code interruptedStage} the stage a CANCELLED analysis
 * stopped in, {@code stageMessage} a transient sub-stage message during FETCHING_POSTING.
 */
public record AnalysisSummary(String id, String title, AnalysisStage stage, Integer score, Integer progress,
        long attemptId, AnalysisStage interruptedStage, String stageMessage, Instant createdAt, Instant updatedAt,
        String errorCode, String errorMessage) {
}
