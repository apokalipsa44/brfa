package pl.example.cvchecker.storage;

import java.nio.file.Path;
import java.time.Instant;

/**
 * The single active workspace CV (docs/API.md §2): metadata, the on-disk PDF and the text extracted
 * at upload. The text is kept so analyses can snapshot it without re-reading a possibly-replaced file.
 */
public record CvContext(String id, String fileName, String extractedText, long sizeBytes, Instant createdAt, Path path) {
}