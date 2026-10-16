package pl.example.cvchecker.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SharedContextTest {

    @Test
    void wrapsCvAndJobInDelimiters() {
        assertThat(SharedContext.of("my cv", "my job"))
                .isEqualTo("<cv>\nmy cv\n</cv>\n<job>\nmy job\n</job>\n");
    }

    @Test
    void truncatesOversizedCvAtTheSharedLimit() {
        String huge = "x".repeat(SharedContext.MAX_CV_CHARS + 500);

        String block = SharedContext.of(huge, "job");

        assertThat(block).contains("[truncated").doesNotContain("x".repeat(SharedContext.MAX_CV_CHARS + 1));
    }

    @Test
    void isByteIdenticalForTheSameAnalysisInput() {
        assertThat(SharedContext.of("cv", "job")).isEqualTo(SharedContext.of("cv", "job"));
    }
}
