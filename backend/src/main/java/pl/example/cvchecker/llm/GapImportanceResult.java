package pl.example.cvchecker.llm;

import java.util.List;

/**
 * Structured output of LLM call #2 for grouped skills (prompts/rank-gaps.st): how important each
 * canonical skill is in the context of the analysed postings, on a 1-5 scale, with a short rationale.
 */
public record GapImportanceResult(List<Rank> skills) {

    public record Rank(String label, int importance, String rationale) {
    }
}
