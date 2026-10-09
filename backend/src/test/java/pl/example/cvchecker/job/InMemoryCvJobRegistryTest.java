package pl.example.cvchecker.job;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryCvJobRegistryTest {

    private final InMemoryCvJobRegistry registry = new InMemoryCvJobRegistry();

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
}
