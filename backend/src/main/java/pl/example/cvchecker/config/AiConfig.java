package pl.example.cvchecker.config;

import java.time.Duration;

import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    @Bean
    OpenAiHttpClientBuilderCustomizer cvCheckerLlmTimeouts(
            @Value("${cvchecker.llm.read-timeout:10m}") Duration readTimeout) {
        return builder -> builder.timeout(readTimeout);
    }

    @Bean
    OpenAiHttpClientBuilderCustomizer cvCheckerLlmCancellation() {
        return builder -> builder.interceptor(new LlmCallInterceptor());
    }
}
