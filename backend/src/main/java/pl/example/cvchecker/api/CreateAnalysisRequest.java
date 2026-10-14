package pl.example.cvchecker.api;

/**
 * {@code POST /api/analyses} body (docs/API.md §4): exactly one of the pasted posting text or the
 * posting link must be present. The link is fetched and parsed by the FETCHING_POSTING stage.
 */
public record CreateAnalysisRequest(String jobPosting, String jobPostingUrl) {
}
