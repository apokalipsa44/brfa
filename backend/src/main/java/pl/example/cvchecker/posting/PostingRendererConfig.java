package pl.example.cvchecker.posting;

import java.net.URI;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fallback when no headless browser is configured: the resolver skips the render step and fails
 * gracefully (docs/API.md §4.1). Replaced by the Playwright renderer when it is enabled/available.
 */
@Configuration
public class PostingRendererConfig {

    @Bean
    @ConditionalOnMissingBean(PostingRenderer.class)
    PostingRenderer unavailablePostingRenderer() {
        return uri -> Optional.empty();
    }
}
