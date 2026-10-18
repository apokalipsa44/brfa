package pl.example.cvchecker.config;

import java.time.Duration;

import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.openai.core.Timeout;

import pl.example.cvchecker.llm.LlmCallInterceptor;

/**
 * HTTP layer for the LLM client (plan §6.3). Spring AI 2.x talks to LM Studio
 * through the official OpenAI SDK over OkHttp; plain {@code http://} URLs stay
 * on HTTP/1.1 by default, so no protocol pinning is needed. The only knob that
 * matters is the timeout: a local model may answer over minutes (plan §6.3.2).
 * The interceptor registers each in-flight call so a cancelled analysis can
 * abort the blocking request (docs/API.md §1.1).
 */
@Configuration
public class AiConfig {

    /**
     * {@code builder.timeout(Duration)} only sets the SDK's <em>request</em> timeout (OkHttp
     * {@code callTimeout}), leaving {@code connect}/{@code read}/{@code write} at the SDK default
     * (60 s). A large prompt on a cold model takes longer than that before the first streamed token,
     * which killed the evaluation/rewrite with {@code Stream failed}. Set every dimension explicitly.
     * <p>
     * Note this client-level timeout is only a floor: Spring AI sends
     * {@code OpenAiChatOptions.timeout} as a per-request {@code RequestOptions} that overrides it,
     * and that option defaults to 60 s — hence the LLM services also set it from
     * {@code cvchecker.llm.read-timeout} (docs/DECISIONS.md).
     */
    @Bean
    OpenAiHttpClientBuilderCustomizer cvCheckerLlmTimeouts(
            @Value("${cvchecker.llm.read-timeout:10m}") Duration readTimeout,
            @Value("${cvchecker.llm.connect-timeout:10s}") Duration connectTimeout) {
        Timeout timeout = Timeout.builder()
                .connect(connectTimeout)
                .read(readTimeout)
                .write(readTimeout)
                .request(readTimeout)
                .build();
        return builder -> builder.timeout(timeout);
    }

    @Bean
    OpenAiHttpClientBuilderCustomizer cvCheckerLlmCancellation() {
        return builder -> builder.interceptor(new LlmCallInterceptor());
    }
}

