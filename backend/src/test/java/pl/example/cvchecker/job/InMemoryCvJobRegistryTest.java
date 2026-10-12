package pl.example.cvchecker.job;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryCvJobRegistryTest {

    @TempDir
    Path tempDir;

    private InMemoryCvJobRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new InMemoryCvJobRegistry(
                new JobFileStore(JsonMapper.builder().build(), tempDir.resolve("jobs.json"), 20));
    }

    @Test
    void createFindRemove() {
        CvJob job = new CvJob("job-1", "desc", Path.of("/tmp/job-1.pdf"));

        registry.create(job);

        assertThat(registry.find("job-1")).containsSame(job);
        assertThat(registry.remove("job-1")).isTrue();
        assertThat(registry.find("job-1")).isEmpty();
    }

    @Test
    void findUnknownJobIsEmpty() {
        assertThat(registry.find("nope")).isEmpty();
    }

    @Test
    void removeUnknownJobReturnsFalse() {
        assertThat(registry.remove("nope")).isFalse();
    }

    @Test
    void duplicateCreateThrows() {
        CvJob job = new CvJob("job-1", "desc", Path.of("/tmp/job-1.pdf"));
        registry.create(job);

        assertThatThrownBy(() -> registry.create(new CvJob("job-1", "other", Path.of("/tmp/x.pdf"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void restoredJobsSurviveRestartAndUnfinishedOnesFailInterrupted() {
        Path storePath = tempDir.resolve("restart.json");
        JobFileStore store = new JobFileStore(JsonMapper.builder().build(), storePath, 20);
        InMemoryCvJobRegistry first = new InMemoryCvJobRegistry(store);
        CvJob running = new CvJob("job-run", "cv.pdf", "desc", Path.of("/tmp/job-run.pdf"), null);
        running.appendStatus(Stage.ANALYZING, "Analyzing", 35);
        first.create(running);

        InMemoryCvJobRegistry second = new InMemoryCvJobRegistry(store);

        CvJob restored = second.find("job-run").orElseThrow();
        assertThat(restored.fileName()).isEqualTo("cv.pdf");
        assertThat(restored.stage()).isEqualTo(Stage.FAILED);
        assertThat(restored.errorCode()).isEqualTo("INTERRUPTED");
    }
}
