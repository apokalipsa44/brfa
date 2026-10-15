package pl.example.cvchecker.api;

/**
 * {@code PATCH /api/analyses/{id}} body (docs/API.md §4): every field is optional and at least one
 * must be present. {@code title} sets the user title; {@code description} sets the user description
 * (blank clears the override); {@code applied} toggles the "applied to job" marker (true = today when
 * unset, false = clear); {@code appliedAt} sets an explicit date and wins over {@code applied}.
 */
public record UpdateAnalysisRequest(String title, String description, Boolean applied, String appliedAt) {
}
