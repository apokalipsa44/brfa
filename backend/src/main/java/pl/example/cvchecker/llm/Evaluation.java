package pl.example.cvchecker.llm;

import java.util.List;

/**
 * Structured output of LLM step #1 (analyze.st): how well the original CV matches the posting. The
 * employer/position pair drives the auto-title (docs/API.md §6.2); they may be empty strings when
 * the post does not name them.
 */
public record Evaluation(String employer, String position, int matchScore, List<String> matchedKeywords,
                         List<String> missingKeywords, List<Suggestion> suggestions, String summary) {
}