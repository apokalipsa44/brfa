package pl.example.cvchecker.gaps;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import pl.example.cvchecker.analysis.Analysis;
import pl.example.cvchecker.analysis.AnalysisRegistry;
import pl.example.cvchecker.api.ApiException;
import pl.example.cvchecker.llm.Evaluation;
import pl.example.cvchecker.llm.GapGroupingResult;
import pl.example.cvchecker.llm.GapGroupingService;
import pl.example.cvchecker.llm.GapImportanceResult;
import pl.example.cvchecker.llm.GapImportanceService;
import pl.example.cvchecker.llm.LlmInvalidOutputException;
import pl.example.cvchecker.llm.ModelContextService;
import pl.example.cvchecker.llm.Requirement;
import pl.example.cvchecker.llm.RequirementStatus;
import pl.example.cvchecker.llm.RequirementType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Regrouping + ranking of skill clusters (docs/API.md §9). */
class SkillClusterServiceTest {

    private final StubRegistry registry = new StubRegistry();
    private final GapService gaps = new GapService(registry, new InMemoryGapRepository());
    private final GapGroupingService grouping = mock(GapGroupingService.class);
    private final GapImportanceService importance = mock(GapImportanceService.class);
    private final ModelContextService models = mock(ModelContextService.class);
    private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
    private final SkillClusterService service = new SkillClusterService(gaps, new InMemorySkillClusterRepository(),
            grouping, importance, registry, models, executor);

    @Test
    void groupsSynonymousPhrasingsAndRanksThem() {
        registry.add(analysis("a1", "Acme · Dev", List.of(
                req("Docker and Kubernetes", RequirementType.MUST, RequirementStatus.MISSING),
                req("AWS", RequirementType.NICE, RequirementStatus.MISSING))));
        registry.add(analysis("a2", "Beta · Dev", List.of(
                req("znajomość rozwiązań Docker i Kubernetes", RequirementType.MUST, RequirementStatus.MISSING))));
        when(grouping.group(anyList(), any())).thenReturn(new GapGroupingResult(List.of(
                new GapGroupingResult.Group("Docker & Kubernetes",
                        List.of("Docker and Kubernetes", "znajomość rozwiązań Docker i Kubernetes")),
                new GapGroupingResult.Group("AWS", List.of("AWS")))));
        when(importance.rank(anyList(), any(), any())).thenReturn(new GapImportanceResult(List.of(
                new GapImportanceResult.Rank("Docker & Kubernetes", 5, "must-have"),
                new GapImportanceResult.Rank("AWS", 2, "occasional"))));

        service.rebuildNow();
        List<GroupedSkill> clusters = service.list();

        assertThat(clusters).extracting(GroupedSkill::label).containsExactly("Docker & Kubernetes", "AWS");
        GroupedSkill docker = clusters.get(0);
        assertThat(docker.importance()).isEqualTo(5);
        assertThat(docker.occurrences()).isEqualTo(2); // one phrasing per analysis
        assertThat(docker.kind()).isEqualTo(GapKind.MISSING);
        assertThat(docker.members()).extracting(GroupedSkill.Member::text)
                .containsExactlyInAnyOrder("Docker and Kubernetes", "znajomość rozwiązań Docker i Kubernetes");
        assertThat(clusters.get(1).occurrences()).isEqualTo(1);
        verify(grouping).group(anyList(), any());
        verify(importance).rank(anyList(), any(), any());
    }

    @Test
    void unmappedTermsBecomeTheirOwnClusterAndImportanceDefaults() {
        registry.add(analysis("a1", "Acme", List.of(
                req("Kubernetes", RequirementType.MUST, RequirementStatus.MISSING),
                req("Terraform", RequirementType.MUST, RequirementStatus.MISSING))));
        when(grouping.group(anyList(), any())).thenReturn(new GapGroupingResult(List.of(
                new GapGroupingResult.Group("Kubernetes", List.of("Kubernetes")))));
        when(importance.rank(anyList(), any(), any())).thenReturn(new GapImportanceResult(List.of(
                new GapImportanceResult.Rank("Kubernetes", 4, "common"))));

        service.rebuildNow();
        List<GroupedSkill> clusters = service.list();

        assertThat(clusters).extracting(GroupedSkill::label).containsExactlyInAnyOrder("Kubernetes", "Terraform");
        GroupedSkill terraform = clusters.stream().filter(c -> c.label().equals("Terraform")).findFirst().orElseThrow();
        assertThat(terraform.importance()).isEqualTo(1); // no model rank -> default
    }

    @Test
    void importanceIsClampedToTheOneToFiveRange() {
        registry.add(analysis("a1", "Acme", List.of(
                req("Kubernetes", RequirementType.MUST, RequirementStatus.MISSING))));
        when(grouping.group(anyList(), any())).thenReturn(new GapGroupingResult(List.of(
                new GapGroupingResult.Group("Kubernetes", List.of("Kubernetes")))));
        when(importance.rank(anyList(), any(), any())).thenReturn(new GapImportanceResult(List.of(
                new GapImportanceResult.Rank("Kubernetes", 9, "too high"))));

        service.rebuildNow();

        assertThat(service.list()).singleElement().extracting(GroupedSkill::importance).isEqualTo(5);
    }

    @Test
    void noGapsClearsTheClustersWithoutCallingTheModel() {
        service.rebuildNow();

        assertThat(service.list()).isEmpty();
        verify(grouping, org.mockito.Mockito.never()).group(anyList(), any());
    }

    @Test
    void invalidModelOutputBecomesA502() {
        registry.add(analysis("a1", "Acme", List.of(
                req("Kubernetes", RequirementType.MUST, RequirementStatus.MISSING))));
        when(grouping.group(anyList(), any()))
                .thenThrow(new LlmInvalidOutputException("bad", null));

        assertThatThrownBy(() -> service.rebuildNow())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    void renamesAClusterLabel() {
        seedOneCluster();
        String id = service.list().get(0).id();

        service.rename(id, "  Cloud & Kubernetes  ");

        assertThat(service.list()).singleElement().extracting(GroupedSkill::label).isEqualTo("Cloud & Kubernetes");
    }

    @Test
    void renameRejectsBlankAndTooLongLabels() {
        seedOneCluster();
        String id = service.list().get(0).id();

        assertThatThrownBy(() -> service.rename(id, "   "))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.code()).isEqualTo("CLUSTER_LABEL_BLANK");
                });
        assertThatThrownBy(() -> service.rename(id, "x".repeat(121)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.code()).isEqualTo("CLUSTER_LABEL_TOO_LONG");
                });
    }

    @Test
    void renameOfAnUnknownClusterIsNotFound() {
        seedOneCluster();

        assertThatThrownBy(() -> service.rename("nope", "Anything"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("CLUSTER_NOT_FOUND"));
    }

    @Test
    void deleteRemovesTheClusterAndItsMemberGaps() {
        seedOneCluster();
        String id = service.list().get(0).id();
        assertThat(gaps.list()).isNotEmpty();

        service.delete(id);

        assertThat(service.list()).isEmpty();
        assertThat(gaps.list()).isEmpty(); // the grouped gap was dismissed
    }

    @Test
    void deleteOfAnUnknownClusterIsNotFound() {
        seedOneCluster();

        assertThatThrownBy(() -> service.delete("nope"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("CLUSTER_NOT_FOUND"));
    }

    /** Seeds one analysis with a single missing requirement and regroups into one cluster. */
    private void seedOneCluster() {
        registry.add(analysis("a1", "Acme", List.of(
                req("Kubernetes", RequirementType.MUST, RequirementStatus.MISSING))));
        when(grouping.group(anyList(), any())).thenReturn(new GapGroupingResult(List.of(
                new GapGroupingResult.Group("Kubernetes", List.of("Kubernetes")))));
        when(importance.rank(anyList(), any(), any())).thenReturn(new GapImportanceResult(List.of(
                new GapImportanceResult.Rank("Kubernetes", 4, "common"))));
        service.rebuildNow();
    }

    private static Requirement req(String text, RequirementType type, RequirementStatus status) {
        return new Requirement(text, type, status, status == RequirementStatus.MISSING ? "" : "evidence");
    }

    private static Analysis analysis(String id, String title, List<Requirement> requirements) {
        Analysis analysis = new Analysis(id, "cv.pdf", "Jan Kowalski", "job", "qwen");
        analysis.setEvaluation(new Evaluation("Acme", "Dev", requirements, 50, List.of(), List.of(),
                List.of(), "summary", "desc"));
        analysis.rename(title);
        return analysis;
    }

    /** Minimal registry stub: aggregation and the posting context only read {@link AnalysisRegistry#findAll()}. */
    private static final class StubRegistry implements AnalysisRegistry {
        private final List<Analysis> analyses = new ArrayList<>();

        void add(Analysis analysis) {
            analyses.add(analysis);
        }

        @Override
        public void create(Analysis analysis) {
            analyses.add(analysis);
        }

        @Override
        public Optional<Analysis> find(String id) {
            return analyses.stream().filter(a -> a.id().equals(id)).findFirst();
        }

        @Override
        public boolean remove(String id) {
            return analyses.removeIf(a -> a.id().equals(id));
        }

        @Override
        public Collection<Analysis> findAll() {
            return analyses;
        }
    }
}
