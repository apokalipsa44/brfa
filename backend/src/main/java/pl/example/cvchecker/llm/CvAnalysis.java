package pl.example.cvchecker.llm;

import java.util.List;

/** Structured output of LLM #1 (analyze.st): how well the original CV matches the posting. */
public record CvAnalysis(int matchScore, List<String> matchedKeywords, List<String> missingKeywords,
                         List<Suggestion> suggestions, String summary) {
}
