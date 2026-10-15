package pl.example.cvchecker.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live check against LM Studio (milestone 7 acceptance): real {@link Evaluation} from the local
 * model over HTTP/1.1. Slow (minutes on a 27B model), so it only runs with
 * {@code LM_STUDIO_LIVE=1} and {@code LLM_MODEL} set, never in the default suite.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "LM_STUDIO_LIVE", matches = "1")
class LmStudioAnalysisLiveTest {

    @Autowired
    EvaluationService service;

    @Test
    void realAnalysisFromLocalModel() {
        String cv = """
                Jan Kowalski, Senior Java Developer, jan@example.com, 2020-2025.
                Experience: Backend Developer at Acme (2020-2025): built Spring Boot microservices,
                PostgreSQL, Docker, CI pipelines. Education: MSc Computer Science. Skills: Java, Spring.
                """;
        String job = "Senior Java Developer wanted: Java, Spring Boot, Kubernetes, English B2.";

        Evaluation evaluation = service.evaluate(cv, job, null);

        assertThat(evaluation.matchScore()).isBetween(0, 100);
        assertThat(evaluation.summary()).isNotBlank();
        assertThat(evaluation.matchedKeywords()).isNotEmpty();
        assertThat(evaluation.suggestions()).isNotEmpty();
    }
}
