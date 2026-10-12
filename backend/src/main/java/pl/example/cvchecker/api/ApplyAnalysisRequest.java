package pl.example.cvchecker.api;

import java.util.List;

/** {@code POST /api/analyses/{id}/apply} body (docs/API.md §4): the suggestion ids the user approved. */
public record ApplyAnalysisRequest(List<String> approvedSuggestionIds) {
}