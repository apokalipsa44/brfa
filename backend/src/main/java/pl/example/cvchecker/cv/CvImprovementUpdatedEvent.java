package pl.example.cvchecker.cv;

import pl.example.cvchecker.api.CvImprovementState;

/** Published on every CV-build stage change; the global SSE stream pushes its payload (docs/API.md §10). */
public record CvImprovementUpdatedEvent(CvImprovementState state) {
}
