package pl.example.cvchecker.job;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * Persists the recent job list as a JSON array of {@link StoredJob} so the queue survives a backend
 * restart (docs/API.md §8). Only the newest {@code history-limit} entries are kept; writes go to a
 * temp file and are moved into place atomically. IO errors are logged and swallowed — persisting
 * history must never break the pipeline.
 */
@Component
public class JobFileStore {

    private static final Logger log = LoggerFactory.getLogger(JobFileStore.class);

    private final ObjectMapper mapper;
    private final Path file;
    private final int limit;

    @Autowired
    public JobFileStore(ObjectMapper mapper,
            @Value("${cvchecker.jobs.store-path:data/cv-jobs.json}") String storePath,
            @Value("${cvchecker.jobs.history-limit:20}") int limit) {
        this(mapper, Path.of(storePath), limit);
    }

    JobFileStore(ObjectMapper mapper, Path file, int limit) {
        this.mapper = mapper;
        this.file = file;
        this.limit = limit;
    }

    /** Reads the persisted jobs (newest first). A missing or corrupt file yields an empty list. */
    public synchronized List<StoredJob> load() {
        if (!Files.exists(file)) return List.of();
        try {
            return new ArrayList<>(List.of(mapper.readValue(file.toFile(), StoredJob[].class)));
        } catch (Exception e) {
            log.warn("Could not read the job store {}: {}", file, e.getMessage());
            return List.of();
        }
    }

    /** Writes the newest {@code limit} jobs (newest first) atomically. */
    public synchronized void save(List<StoredJob> jobs) {
        List<StoredJob> trimmed = jobs.stream()
                .sorted(Comparator.comparing(StoredJob::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .toList();
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, "cv-jobs-", ".json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), trimmed);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            log.warn("Could not write the job store {}: {}", file, e.getMessage());
        }
    }
}
