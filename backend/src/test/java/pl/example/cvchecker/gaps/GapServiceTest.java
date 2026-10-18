package pl.example.cvchecker.gaps;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import pl.example.cvchecker.analysis.Analysis;
import pl.example.cvchecker.analysis.AnalysisRegistry;
import pl.example.cvchecker.api.ApiException;
import pl.example.cvchecker.llm.Evaluation;
import pl.example.cvchecker.llm.Requirement;
import pl.example.cvchecker.llm.RequirementStatus;
import pl.example.cvchecker.llm.RequirementType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Aggregation + CRUD of skill gaps (docs/API.md §9). */
class GapServiceTest {

    private final StubRegistry registry = new StubRegistry();
    private final GapService service = new GapService(registry, new InMemoryGapRepository());

    @Test
    void aggregatesMissingAndNiceAcrossAnalysesDeduplicatedAndCounted() {
        registry.add(analysis("a1", "Acme · Dev", List.of(
                req("Kubernetes", RequirementType.MUST, RequirementStatus.MISSING),
                req("Java", RequirementType.MUST, RequirementStatus.MET),
                req("Terraform", RequirementType.NICE, RequirementStatus.MET),
                req("Communication", RequirementType.SOFT, RequirementStatus.MET))));
        registry.add(analysis("a2", "Beta · Dev", List.of(
                req("  kubernetes ", RequirementType.NICE, RequirementStatus.PARTIAL),
                req("AWS", RequirementType.NICE, RequirementStatus.MISSING))));

        List<GapItem> items = service.list();

        assertThat(items).extracting(GapItem::text).containsExactly("Kubernetes", "AWS", "Terraform");
        GapItem k8s = items.get(0);
        assertThat(k8s.kind()).isEqualTo(GapKind.MISSING); // missing in a1 wins over nice in a2
        assertThat(k8s.occurrences()).isEqualTo(2);
        assertThat(k8s.sources()).extracting(GapItem.GapSource::analysisId).containsExactly("a1", "a2");
        assertThat(k8s.origin()).isEqualTo("DERIVED");
        assertThat(k8s.status()).isEqualTo(GapStatus.OPEN);

        GapItem aws = items.get(1);
        assertThat(aws.kind()).isEqualTo(GapKind.MISSING);
        assertThat(aws.occurrences()).isEqualTo(1);
        assertThat(aws.sources()).extracting(GapItem.GapSource::analysisId).containsExactly("a2");

        assertThat(items.get(2).kind()).isEqualTo(GapKind.NICE); // Terraform: nice-to-have, met
    }

    @Test
    void sameAnalysisRaisesATermOnlyOnce() {
        registry.add(analysis("a1", "Acme", List.of(
                req("Kubernetes", RequirementType.MUST, RequirementStatus.MISSING),
                req("Kubernetes", RequirementType.NICE, RequirementStatus.MISSING))));

        assertThat(service.list()).singleElement().extracting(GapItem::occurrences).isEqualTo(1);
    }

    @Test
    void analysesWithoutEvaluationOrBlankTextAreIgnored() {
        registry.add(analysis("a1", "Acme", List.of(req("   ", RequirementType.MUST, RequirementStatus.MISSING))));
        registry.add(new Analysis("a2", "cv.pdf", "text", "job", "qwen"));

        assertThat(service.list()).isEmpty();
    }

    @Test
    void updateSetsStatusAndNoteAndKeepsAggregation() {
        registry.add(analysis("a1", "Acme", List.of(
                req("Kubernetes", RequirementType.MUST, RequirementStatus.MISSING))));
        String id = service.list().get(0).id();

        GapItem updated = service.update(id, null, null, GapStatus.LEARNED, "took a course");

        assertThat(updated.status()).isEqualTo(GapStatus.LEARNED);
        assertThat(updated.note()).isEqualTo("took a course");
        assertThat(updated.occurrences()).isEqualTo(1); // still aggregated
    }

    @Test
    void manualGapIsCreatedWithZeroOccurrences() {
        GapItem created = service.create("GraphQL", GapKind.NICE, "want to learn");

        assertThat(created.origin()).isEqualTo("MANUAL");
        assertThat(created.occurrences()).isZero();
        assertThat(created.kind()).isEqualTo(GapKind.NICE);
        assertThat(service.list()).singleElement().extracting(GapItem::text).isEqualTo("GraphQL");
    }

    @Test
    void deletingAManualGapRemovesIt() {
        GapItem created = service.create("GraphQL", null, null);

        service.delete(created.id());

        assertThat(service.list()).isEmpty();
    }

    @Test
    void deletingADerivedGapDismissesItSoItDoesNotReappear() {
        registry.add(analysis("a1", "Acme", List.of(
                req("Kubernetes", RequirementType.MUST, RequirementStatus.MISSING))));
        String id = service.list().get(0).id();

        service.delete(id);

        assertThat(service.list()).isEmpty(); // a later sync must not resurrect it
    }

    @Test
    void blankTextIsRejectedAndUnknownIdsAre404() {
        assertThatThrownBy(() -> service.create("  ", null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("non-blank");
        assertThatThrownBy(() -> service.update("nope", null, null, GapStatus.OPEN, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.delete("nope")).isInstanceOf(ApiException.class);
    }

    @Test
    void emptyUpdateIsRejected() {
        GapItem created = service.create("GraphQL", null, null);
        assertThatThrownBy(() -> service.update(created.id(), null, null, null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("at least one");
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

    /** Minimal registry stub: gap aggregation only reads {@link AnalysisRegistry#findAll()}. */
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
