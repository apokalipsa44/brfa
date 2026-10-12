package pl.example.cvchecker.analysis;

/** Published when an analysis is removed (SSE event {@code analysis.deleted}, docs/API.md §7). */
public record AnalysisDeletedEvent(String analysisId) {
}