package pl.example.cvchecker.analysis;

/**
 * Transient live progress of an LLM step (docs/API.md §7): a chunk of the model output as it is
 * generated. Never buffered or replayed, and never logged (untrusted content, AGENTS.md).
 */
public record AnalysisOutputEvent(String analysisId, String stage, String delta) {
}
