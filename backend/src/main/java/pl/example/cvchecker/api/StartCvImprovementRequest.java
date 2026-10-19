package pl.example.cvchecker.api;

import java.util.List;

/** Body of {@code POST /api/cv/improvement} (docs/API.md §10): the skills to add to the CV. */
public record StartCvImprovementRequest(List<String> skills) {
}
