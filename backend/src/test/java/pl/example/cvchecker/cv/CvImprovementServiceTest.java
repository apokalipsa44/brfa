package pl.example.cvchecker.cv;

import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import pl.example.cvchecker.api.ApiException;
import pl.example.cvchecker.api.CvImprovementState;
import pl.example.cvchecker.llm.CvBuildService;
import pl.example.cvchecker.llm.CvChange;
import pl.example.cvchecker.llm.CvDocument;
import pl.example.cvchecker.llm.CvRewriteResult;
import pl.example.cvchecker.llm.ExperienceEntry;
import pl.example.cvchecker.llm.ModelContextService;
import pl.example.cvchecker.pdf.CvPdfRenderer;
import pl.example.cvchecker.storage.CvContext;
import pl.example.cvchecker.storage.CvContextStore;
import pl.example.cvchecker.storage.TempFileStorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The standalone CV builder orchestrator (docs/API.md §10). */
class CvImprovementServiceTest {

    private final CvContextStore cvStore = mock(CvContextStore.class);
    private final ModelContextService models = mock(ModelContextService.class);
    private final CvBuildService builder = mock(CvBuildService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);

    private CvImprovementService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new CvImprovementService(cvStore, models, builder, new CvPdfRenderer(),
                new TempFileStorage(), events, executor);
        when(cvStore.current()).thenReturn(Optional.of(new CvContext("cv1", "cv.pdf",
                "Jan Kowalski — Java developer", 10, Instant.now(), null)));
        when(models.isReady()).thenReturn(true);
        when(models.activeModelId()).thenReturn("qwen");
        when(builder.build(anyString(), anyList(), any(), any())).thenReturn(rewrite());
        // Run the build inline so the test observes the terminal state synchronously.
        when(executor.submit(any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        });
    }

    @Test
    void buildsAndCompletesWithADownloadableResult() throws Exception {
        CvImprovementState state = service.start(List.of("Kubernetes", "Kubernetes", " GraphQL "));

        assertThat(state.stage()).isEqualTo(CvImprovementStage.COMPLETED);
        assertThat(state.skills()).containsExactly("Kubernetes", "GraphQL");
        assertThat(state.modelId()).isEqualTo("qwen");
        assertThat(state.resultUrl()).startsWith("/api/cv/improvement/result?v=");
        assertThat(Files.exists(service.resultPath())).isTrue();
        verify(events, org.mockito.Mockito.atLeastOnce()).publishEvent(any(CvImprovementUpdatedEvent.class));
    }

    @Test
    void emptySkillsAreRejected() {
        assertThatThrownBy(() -> service.start(List.of("  ", "")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "IMPROVEMENT_SKILLS_EMPTY");
    }

    @Test
    void tooManySkillsAreRejected() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < CvImprovementService.MAX_SKILLS + 1; i++) {
            many.add("Skill " + i);
        }
        assertThatThrownBy(() -> service.start(many))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "IMPROVEMENT_TOO_MANY_SKILLS");
    }

    @Test
    void overlongSkillIsRejected() {
        assertThatThrownBy(() -> service.start(List.of("x".repeat(CvImprovementService.MAX_SKILL_CHARS + 1))))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "IMPROVEMENT_SKILL_TOO_LONG");
    }

    @Test
    void missingCvIsRejected() {
        when(cvStore.current()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(List.of("Kubernetes")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "CV_MISSING");
    }

    @Test
    void unreadyModelIsRejected() {
        when(models.isReady()).thenReturn(false);

        assertThatThrownBy(() -> service.start(List.of("Kubernetes")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "MODEL_NOT_READY");
    }

    @Test
    void aSecondBuildWhileOneRunsIsRejected() throws Exception {
        // A fresh executor mock with no inline stub: the worker never runs, so the build stays REWRITING.
        ThreadPoolTaskExecutor idle = mock(ThreadPoolTaskExecutor.class);
        service = new CvImprovementService(cvStore, models, builder, new CvPdfRenderer(),
                new TempFileStorage(), events, idle);

        CvImprovementState first = service.start(List.of("Kubernetes"));
        assertThat(first.stage()).isEqualTo(CvImprovementStage.REWRITING);

        assertThatThrownBy(() -> service.start(List.of("GraphQL")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "IMPROVEMENT_RUNNING");
    }

    @Test
    void stateIsEmptyBeforeTheFirstBuild() {
        CvImprovementState state = service.state();

        assertThat(state.stage()).isEqualTo(CvImprovementStage.NONE);
        assertThat(state.skills()).isEmpty();
        assertThat(state.resultUrl()).isNull();
    }

    private static CvRewriteResult rewrite() {
        CvDocument document = new CvDocument("Jan Kowalski", "Java Developer", "jan@example.com", null, null,
                List.of(), "Senior Java dev",
                List.of(new ExperienceEntry("Backend Developer", "Acme", "2020-2025", List.of("Built services"))),
                List.of(), List.of("Backend: Java, Kubernetes"), List.of(), List.of());
        return new CvRewriteResult(document, List.of(new CvChange("Skills", "", "Kubernetes", "User selected")));
    }
}
