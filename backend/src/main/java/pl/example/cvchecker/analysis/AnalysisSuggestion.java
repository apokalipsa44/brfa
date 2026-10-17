package pl.example.cvchecker.analysis;

import pl.example.cvchecker.llm.Priority;

/**
 * A suggestion as the review UI sees it (docs/API.md §4): the evaluation's LLM suggestion plus the
 * backend-issued stable {@code id} (the ids are generated here, never by the LLM). {@code
 * needsConfirmation} is set by the evaluation sanitizer when the proposed {@code after} text mentions
 * words the CV does not contain, so the UI leaves the suggestion unchecked.
 */
public record AnalysisSuggestion(String id, String section, String problem, String proposal,
        String before, String after, Priority priority, boolean needsConfirmation) {
}
