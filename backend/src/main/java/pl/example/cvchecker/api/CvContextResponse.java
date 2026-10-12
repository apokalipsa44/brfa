package pl.example.cvchecker.api;

/** Response of POST /api/cv and GET /api/cv (docs/API.md §2). */
public record CvContextResponse(String id, String fileName, String status) {
}