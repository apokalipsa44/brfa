package pl.example.cvchecker.analysis;

import pl.example.cvchecker.api.AnalysisSummary;

/** Published on every stage/progress/title/score change; the global SSE stream pushes its payload. */
public record AnalysisUpdatedEvent(AnalysisSummary summary) {
}