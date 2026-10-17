package pl.example.cvchecker.analysis;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import pl.example.cvchecker.llm.Evaluation;
import pl.example.cvchecker.llm.Priority;
import pl.example.cvchecker.llm.Suggestion;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQLite persistence for the analysis registry (docs/API.md §8): write-through CRUD, restore across a
 * simulated restart, and the restart-interrupted → FAILED/retryable rule.
 */
class SqliteAnalysisRegistryTest {

    @TempDir
    Path tempDir;

    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher events = published::add;

    private SqliteAnalysisRegistry registry;

    @BeforeEach
    void setUp() {
        registry = newRegistry();
    }

    private SqliteAnalysisRegistry newRegistry() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("cvchecker.db"));
        return new SqliteAnalysisRegistry(new JdbcTemplate(dataSource),
                JsonMapper.builder().build(), events);
    }

    private static Analysis analysis(String id) {
        return new Analysis(id, "cv.pdf", "Jan Kowalski, Java", "Senior Java Developer", "qwen/iq3_s.gguf");
    }

    private static Evaluation evaluation() {
        return new Evaluation("Acme", "Senior Java Developer", List.of(), 58, List.of("Java"), List.of("Kubernetes"),
                List.of(new Suggestion("Skills", "Missing K8s", "Group container skills", "Docker",
                        "Docker, Kubernetes (basics)", Priority.HIGH, false)),
                "Solid match.", "Payment APIs in Java on a small team.");
    }

    @Test
    void createFindAndList() {
        registry.create(analysis("a1"));

        assertThat(registry.find("a1")).isPresent();
        assertThat(registry.findAll()).hasSize(1);
        assertThat(published).isNotEmpty();
    }

    @Test
    void removeDeletesTheRowForGood() {
        registry.create(analysis("a1"));

        assertThat(registry.remove("a1")).isTrue();
        assertThat(registry.remove("a1")).isFalse();
        assertThat(published).anyMatch(e -> e instanceof AnalysisDeletedEvent);

        assertThat(newRegistry().findAll()).isEmpty();
    }

    @Test
    void restoresStateAcrossInstances() {
        Analysis original = analysis("a1");
        original.setEvaluation(evaluation());
        original.rename("My title");
        original.setApplied(LocalDate.of(2026, 10, 14));
        original.transition(AnalysisStage.AWAITING_REVIEW, 40);
        registry.create(original);

        Analysis restored = newRegistry().find("a1").orElseThrow();

        assertThat(restored.title()).isEqualTo("My title");
        assertThat(restored.appliedAt()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(restored.stage()).isEqualTo(AnalysisStage.AWAITING_REVIEW);
        assertThat(restored.score()).isEqualTo(58);
        assertThat(restored.shortDescription()).isEqualTo("Payment APIs in Java on a small team.");
        assertThat(restored.suggestions()).extracting("id").containsExactly("s1");
        assertThat(restored.postingText()).isEqualTo("Senior Java Developer");
    }

    @Test
    void userDescriptionSurvivesRestoreAndWinsOverEvaluation() {
        Analysis original = analysis("a1");
        original.setEvaluation(evaluation());
        original.setDescription("Mine.");
        registry.create(original);

        Analysis restored = newRegistry().find("a1").orElseThrow();
        assertThat(restored.shortDescription()).isEqualTo("Mine.");

        restored.setDescription(null); // clearing falls back to the evaluation value
        assertThat(restored.shortDescription()).isEqualTo("Payment APIs in Java on a small team.");
    }

    @Test
    void runningAnalysisIsRestoredFailedAndRetryable() {
        Analysis original = analysis("a1");
        original.transition(AnalysisStage.EVALUATING, 15);
        registry.create(original);

        Analysis restored = newRegistry().find("a1").orElseThrow();

        assertThat(restored.stage()).isEqualTo(AnalysisStage.FAILED);
        assertThat(restored.errorCode()).isEqualTo(SqliteAnalysisRegistry.INTERRUPTED);
        assertThat(restored.resetForRetry()).isTrue();
        assertThat(restored.stage()).isEqualTo(AnalysisStage.EVALUATING);
    }

    @Test
    void cancelledAnalysisIsRestoredAsIs() {
        Analysis original = analysis("a1");
        original.transition(AnalysisStage.REWRITING, 65);
        original.cancel();
        registry.create(original);

        Analysis restored = newRegistry().find("a1").orElseThrow();

        assertThat(restored.stage()).isEqualTo(AnalysisStage.CANCELLED);
        assertThat(restored.interruptedStage()).isEqualTo(AnalysisStage.REWRITING);
    }
}
