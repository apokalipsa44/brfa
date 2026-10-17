package pl.example.cvchecker.llm;

/**
 * One improvement suggestion from the evaluation (docs/API.md §4/§6). The id is assigned later by
 * the analysis domain so the review UI can reference stable ids; {@code before}/{@code after} may be
 * empty strings (or null) when the edit has no direct text pair. {@code needsConfirmation} is set by
 * the analysis sanitizer (not the model) when {@code after} contains words absent from the CV, so the
 * UI can leave the suggestion unchecked and flag it for the user.
 */
public record Suggestion(String section, String problem, String proposal, String before, String after,
        Priority priority, boolean needsConfirmation) {
}
