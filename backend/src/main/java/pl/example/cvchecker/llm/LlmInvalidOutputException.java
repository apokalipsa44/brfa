package pl.example.cvchecker.llm;

/** The LLM returned invalid structured output after all retries (plan §6.7). */
public class LlmInvalidOutputException extends RuntimeException {

    public LlmInvalidOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
