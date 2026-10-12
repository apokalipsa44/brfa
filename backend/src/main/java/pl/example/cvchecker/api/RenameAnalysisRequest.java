package pl.example.cvchecker.api;

/** {@code PATCH /api/analyses/{id}} body (docs/API.md §4): the user's own title (never auto-overwritten). */
public record RenameAnalysisRequest(String title) {
}