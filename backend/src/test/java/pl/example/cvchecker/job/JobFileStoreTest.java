package pl.example.cvchecker.job;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class JobFileStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void saveKeepsOnlyNewestJobsAndLoadRoundTrips() {
        JobFileStore store = new JobFileStore(JsonMapper.builder().build(), tempDir.resolve("jobs.json"), 20);
        List<StoredJob> jobs = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            jobs.add(new StoredJob("job-" + i, "cv.pdf", Stage.COMPLETED,
                    Instant.parse("2026-10-01T00:00:00Z").plusSeconds(i), 100, null, null, null, null));
        }

        store.save(jobs);
        List<StoredJob> loaded = store.load();

        assertThat(loaded).hasSize(20);
        assertThat(loaded).extracting(StoredJob::jobId).first().isEqualTo("job-24");
        assertThat(loaded).extracting(StoredJob::jobId).doesNotContain("job-0", "job-4");
    }

    @Test
    void missingFileLoadsEmpty() {
        JobFileStore store = new JobFileStore(JsonMapper.builder().build(), tempDir.resolve("missing.json"), 20);
        assertThat(store.load()).isEmpty();
    }
}
