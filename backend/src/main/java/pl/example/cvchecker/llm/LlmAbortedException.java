package pl.example.cvchecker.llm;

/**
 * Thrown when an LLM call is aborted because its analysis run was cancelled (docs/API.md §1.1).
 * Distinct from {@link LlmInvalidOutputException}: the pipeline treats it as "stop quietly", never
 * as a failure, because {@link pl.example.cvchecker.analysis.Analysis#cancel()} already recorded the
 * CANCELLED state.
 */
public class LlmAbortedException extends RuntimeException {

    public LlmAbortedException(String message) {
        super(message);
    }
}
