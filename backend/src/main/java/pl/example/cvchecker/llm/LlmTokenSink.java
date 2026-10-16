package pl.example.cvchecker.llm;

/**
 * Receives streamed LLM output deltas as they are generated, so the pipeline can show live progress.
 * Content is model output (derived from the user's own CV/posting) and must never be logged
 * (AGENTS.md).
 */
@FunctionalInterface
public interface LlmTokenSink {

    /** No-op sink used when the caller does not want streaming. */
    LlmTokenSink NONE = delta -> {
    };

    void onToken(String delta);
}
