package pl.example.cvchecker.config;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

import com.openai.core.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Guards against the regression where only the SDK request timeout was set, leaving OkHttp's read
 * timeout at its 60 s default — a cold large prompt then died with {@code Stream failed}
 * (docs/DECISIONS.md).
 */
class AiConfigTest {

    @Test
    void appliesTheReadTimeoutToEveryHttpDimension() {
        SpringAiOpenAiHttpClient.Builder builder = mock(SpringAiOpenAiHttpClient.Builder.class);

        OpenAiHttpClientBuilderCustomizer customizer =
                new AiConfig().cvCheckerLlmTimeouts(Duration.ofMinutes(10), Duration.ofSeconds(10));
        customizer.customize(builder);

        ArgumentCaptor<Timeout> captor = ArgumentCaptor.forClass(Timeout.class);
        verify(builder).timeout(captor.capture());
        Timeout timeout = captor.getValue();
        assertThat(timeout.connect()).isEqualTo(Duration.ofSeconds(10));
        assertThat(timeout.read()).isEqualTo(Duration.ofMinutes(10));
        assertThat(timeout.write()).isEqualTo(Duration.ofMinutes(10));
        assertThat(timeout.request()).isEqualTo(Duration.ofMinutes(10));
    }
}
