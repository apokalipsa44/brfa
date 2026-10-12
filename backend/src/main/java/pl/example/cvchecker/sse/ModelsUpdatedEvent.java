package pl.example.cvchecker.sse;

/** Published after the active model selection changes (SSE event {@code models.updated}, docs/API.md §3). */
public record ModelsUpdatedEvent(String activeModelId) {
}