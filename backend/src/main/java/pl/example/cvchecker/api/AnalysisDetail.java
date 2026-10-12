package pl.example.cvchecker.api;

import java.time.Instant;
import java.util.List;

import pl.example.cvchecker.analysis.AnalysisSuggestion;
import pl.example.cvchecker.analysis.AnalysisStage;
import pl.example.cvchecker.llm.CvChange;

/**
 * Analysis detail (docs/API.md §4): the summary plus the evaluation payload once stage 1 finished.
 * {@code summary}/{@code matchedKeywords}/{@code missingKeywords}/{@code suggestions} are present
 * after evaluation, {@code appliedChanges} after the rewrite, {@code resultUrl} only when COMPLETED.
 */
public record AnalysisDetail(String id, String title, AnalysisStage stage, Integer score, Integer progress,
        Instant createdAt, Instant updatedAt, String modelId, String errorCode, String errorMessage,
        String summary, List<String> matchedKeywords, List<String> missingKeywords,
        List<AnalysisSuggestion> suggestions, List<CvChange> appliedChanges, String resultUrl) {
}