package pl.example.cvchecker.posting;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bounds for resolving a link posting (docs/API.md §8, {@code cvchecker.posting-fetch.*}). All values
 * have safe defaults so the feature works without explicit configuration.
 */
@ConfigurationProperties(prefix = "cvchecker.posting-fetch")
public record PostingFetchProperties(
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("15s") Duration readTimeout,
        @DefaultValue("30s") Duration totalTimeout,
        @DefaultValue("2000000") int maxBytes,
        @DefaultValue("5") int maxRedirects,
        @DefaultValue("400") int minTextLength,
        @DefaultValue("true") boolean browserEnabled,
        @DefaultValue("CvCheckerBot/1.0 (+https://cvchecker.local; job-posting-reader)") String userAgent,
        @DefaultValue("1") int maxConcurrentBrowser) {
}
