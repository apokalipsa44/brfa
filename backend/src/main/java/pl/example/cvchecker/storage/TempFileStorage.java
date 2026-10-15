package pl.example.cvchecker.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

/**
 * Temp files for the active CV PDF and analysis result PDFs live in a per-process temp directory
 * (wiped with the process).
 */
@Component
public class TempFileStorage {

    private final Path root;

    public TempFileStorage() throws IOException {
        this(Files.createTempDirectory("cvchecker-"));
    }

    TempFileStorage(Path root) {
        this.root = root;
    }

    /** Stores the persistent CV PDF as {@code <root>/cv-<cvId>.pdf} (docs/API.md §2). */
    public Path storeCv(String cvId, byte[] data) throws IOException {
        Path file = root.resolve("cv-" + cvId + ".pdf");
        Files.write(file, data);
        return file;
    }

    public Path root() {
        return root;
    }
}
