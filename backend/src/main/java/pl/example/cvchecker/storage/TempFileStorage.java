package pl.example.cvchecker.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

/** Job files (original PDFs now, result PDFs later) live in a per-run temp directory. */
@Component
public class TempFileStorage {

    private final Path root;

    public TempFileStorage() throws IOException {
        this(Files.createTempDirectory("cvchecker-"));
    }

    TempFileStorage(Path root) {
        this.root = root;
    }

    /** Stores the uploaded original PDF as {@code <root>/<jobId>.pdf} and returns its path. */
    public Path storeOriginal(String jobId, byte[] data) throws IOException {
        Path file = root.resolve(jobId + ".pdf");
        Files.write(file, data);
        return file;
    }

    public Path root() {
        return root;
    }
}
