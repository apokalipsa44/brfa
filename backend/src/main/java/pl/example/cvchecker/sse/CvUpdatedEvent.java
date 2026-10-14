package pl.example.cvchecker.sse;

/** Published after the active workspace CV is uploaded or replaced (SSE event {@code cv.updated}, docs/API.md §2). */
public record CvUpdatedEvent(String cvId) {
}
