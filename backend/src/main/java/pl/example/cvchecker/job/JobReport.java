package pl.example.cvchecker.job;

import java.util.List;

import pl.example.cvchecker.llm.CvChange;
import pl.example.cvchecker.llm.Suggestion;

/**
 * Completion report shown on the job card (docs/API.md, plan §6.7): the LLM #1 assessment of the
 * <em>original</em> CV plus the LLM #2 changelog. Sent in the {@code completed} event and the
 * snapshot endpoint.
 */
public record JobReport(int matchScoreBefore, List<String> matchedKeywords, List<String> missingKeywords,
        List<Suggestion> suggestions, String summary, List<CvChange> changes) {
}
