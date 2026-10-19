package pl.example.cvchecker.api;

/** Body of {@code PATCH /api/skill-gaps/clusters/{id}} (docs/API.md §9). */
public record UpdateClusterRequest(String label) {
}
