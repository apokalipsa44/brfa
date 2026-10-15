package pl.example.cvchecker.posting;

/** Structured job data read from schema.org {@code JobPosting} JSON-LD (docs/API.md §4.1/§6.2). */
public record StructuredJobPosting(String title, String employer, String description) {
}
