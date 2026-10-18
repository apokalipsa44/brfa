package pl.example.cvchecker.config;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the fix where the SDK's own per-request timeout (default 60s) aborted long cold prompts:
 * {@code spring.ai.openai.timeout} must be raised along with the OkHttp timeouts (docs/DECISIONS.md).
 */
@SpringBootTest(properties = "cvchecker.persistence=memory")
class OpenAiTimeoutBindingTest {

    @Autowired
    private OpenAiCommonProperties properties;

    @Test
    void openAiSdkTimeoutExceedsTheSixtySecondDefault() {
        assertThat(properties.getTimeout()).isGreaterThan(Duration.ofSeconds(60));
    }
}
