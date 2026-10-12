package pl.example.cvchecker.api;

/** {@code POST /api/analyses} body (docs/API.md §4): the posting text processed against the CV context. */
public record CreateAnalysisRequest(String jobPosting) {
}