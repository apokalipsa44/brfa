package pl.example.cvchecker.analysis;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import pl.example.cvchecker.llm.CvChange;
import pl.example.cvchecker.llm.CvDocument;
import pl.example.cvchecker.llm.Evaluation;

/**
 * Full persisted snapshot of one analysis (docs/API.md §8): every field needed to rebuild an
 * {@link Analysis} after a restart. The persistence layer serializes this record to JSON in the
 * SQLite {@code state} column; {@link Analysis#toState()} / {@link Analysis#fromState} own the
 * mapping. The transient {@code stageMessage} is deliberately not persisted (docs/API.md §4).
 */
public record AnalysisState(
        String id,
        String cvFileName,
        String cvText,
        String sourceUrl,
        String modelId,
        Instant createdAt,
        Instant updatedAt,
        AnalysisStage stage,
        int progress,
        String title,
        boolean userEditedTitle,
        String userDescription,
        LocalDate appliedAt,
        Integer score,
        Evaluation evaluation,
        List<AnalysisSuggestion> suggestions,
        List<String> approvedSuggestionIds,
        List<CvChange> appliedChanges,
        CvDocument rewrittenDocument,
        String resultFile,
        String errorCode,
        String errorMessage,
        AnalysisStage failedStage,
        AnalysisStage interruptedStage,
        long attemptId,
        String postingText,
        PostingSource postingSource,
        boolean postingTruncated,
        String postingEmployer,
        String postingPosition) {
}
