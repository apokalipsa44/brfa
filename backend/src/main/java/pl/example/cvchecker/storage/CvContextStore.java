package pl.example.cvchecker.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import pl.example.cvchecker.sse.CvUpdatedEvent;

/**
 * The single active workspace CV (docs/API.md §2): stored once and reused by every analysis.
 * Uploading a new CV replaces the current one; each analysis snapshots the text at creation, so a
 * replacement never affects an existing analysis. Every successful placement broadcasts
 * {@code cv.updated} so other open tabs refresh the CV chip.
 */
@Component
public class CvContextStore {

    private final TempFileStorage storage;
    private final ApplicationEventPublisher events;
    private CvContext current;

    public CvContextStore(TempFileStorage storage, ApplicationEventPublisher events) {
        this.storage = storage;
        this.events = events;
    }

    /** Stores the uploaded PDF as the current CV and removes the previously stored one. */
    public synchronized CvContext place(String fileName, byte[] data, String extractedText) throws IOException {
        String id = UUID.randomUUID().toString();
        Path path = storage.storeCv(id, data);
        CvContext previous = this.current;
        this.current = new CvContext(id, fileName, extractedText, data.length, Instant.now(), path);
        deleteQuietly(previous);
        events.publishEvent(new CvUpdatedEvent(id));
        return this.current;
    }

    /** The current CV, if any. */
    public synchronized Optional<CvContext> current() {
        return Optional.ofNullable(current);
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