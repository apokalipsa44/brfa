package pl.example.cvchecker.llm;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live check against LM Studio (milestone 8 acceptance): the rewrite returns valid JSON and,
 * crucially, does not fabricate facts. The test CV knows Java/Spring only while the posting
 * demands Kubernetes — K8s must appear in missing keywords but never in the rewritten document.
 * Slow (minutes), so it only runs with {@code LM_STUDIO_LIVE=1} and {@code LLM_MODEL} set.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "LM_STUDIO_LIVE", matches = "1")
class LmStudioRewriteLiveTest {

    @Autowired
    EvaluationService evaluationService;

    @Autowired
    CvRewriteService rewriteService;

    @Test
    void rewriteKeepsFactsAndAddsNoKubernetes() {
        String cv = """
                Jan Kowalski, Backend Developer, jan@example.com, Warsaw.
                Experience: Backend Developer at Acme (2020-2025): built Spring Boot microservices
                with PostgreSQL and Docker. Education: MSc Computer Science, Warsaw University of
                Technology (2015-2020). Skills: Java, Spring Boot, PostgreSQL, Docker.
                """;
        String job = "Senior Java Developer wanted: Java, Spring Boot, Kubernetes, English B2. "
                + "Deploy and operate services on Kubernetes clusters.";

        Evaluation evaluation = evaluationService.evaluate(cv, job);
        assertThat(evaluation.missingKeywords().toString().toLowerCase()).contains("kubernetes");

        CvRewriteResult result = rewriteService.rewrite(cv, job, evaluation, evaluation.suggestions(), null);

        assertThat(result.document().fullName()).containsIgnoringCase("Kowalski");
        assertThat(result.changes()).isNotEmpty();
        String documentText = (result.document().toString() + result.changes().toString()).toLowerCase();
        assertThat(documentText).doesNotContain("kubernetes");
    }
}
