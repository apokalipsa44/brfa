package pl.example.cvchecker.llm;

/**
 * One improvement suggestion from the evaluation (docs/API.md §4/§6). The id is assigned later by
 * the analysis domain so the review UI can reference stable ids; {@code before}/{@code after} may be
 * empty strings (or null) when the edit has no direct text pair.
 */
public record Suggestion(String section, String problem, String proposal, String before, String after,
        Priority priority) {
}