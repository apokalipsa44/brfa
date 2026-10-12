package pl.example.cvchecker.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * The single active workspace CV (docs/API.md §2): stored once and reused by every analysis/old job.
 * Uploading a new CV replaces the current one; jobs copy the bytes at creation, so a replacement
 * never affects a queued/running job.
 */
@Component
public class CvContextStore {

    private final TempFileStorage storage;
    private CvContext current;

    public CvContextStore(TempFileStorage storage) {
        this.storage = storage;
    }

    /** Stores the uploaded PDF as the current CV and removes the previously stored one. */
    public synchronized CvContext place(String fileName, byte[] data, String extractedText) throws IOException {
        String id = UUID.randomUUID().toString();
        Path path = storage.storeCv(id, data);
        CvContext previous = this.current;
        this.current = new CvContext(id, fileName, extractedText, data.length, Instant.now(), path);
        deleteQuietly(previous);
        return this.current;
    }

    /** The current CV, if any. */
    public synchronized Optional<CvContext> current() {
        return Optional.ofNullable(current);
    }

    /** The current CV when its id matches — an unknown or already-replaced id is treated as missing. */
    public synchronized Optional<CvContext> find(String id) {
        if (current == null || !current.id().equals(id)) {
            return Optional.empty();
        }
        return Optional.of(current);
    }

    /** Best-effort deletion: the temp dir is wiped with the process anyway. */
    private static void deleteQuietly(CvContext file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file.path());
        } catch (Exception e) {
            // ignore — nothing to recover here
        }
    }
}