package pl.example.cvchecker.posting;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSON-LD extraction for schema.org JobPosting (docs/API.md §4.1). It ignores malformed blocks and
 * handles arrays and {@code @graph}.
 */
class JsonLdExtractorTest {

    private final JsonLdExtractor extractor = new JsonLdExtractor(new ObjectMapper());

    @Test
    void extractsSingleJobPosting() {
        String html = """
                <html><body>
                <script type="application/ld+json">{
                  "@context": "https://schema.org",
                  "@type": "JobPosting",
                  "title": "Senior Java Developer",
                  "hiringOrganization": {"name": "Acme Corp"},
                  "description": "<p>Work with Spring and Kotlin.</p>"
                }</script>
                </body></html>
                """;

        Optional<StructuredJobPosting> found = extractor.extract(html);

        assertThat(found).isPresent();
        assertThat(found.get().title()).isEqualTo("Senior Java Developer");
        assertThat(found.get().employer()).isEqualTo("Acme Corp");
        assertThat(found.get().description()).isEqualTo("Work with Spring and Kotlin.");
    }

    @Test
    void extractsFromArray() {
        String html = """
                <script type="application/ld+json">[
                  {"@type": "Organization", "name": "X"},
                  {"@context":"https://schema.org","@type":"JobPosting","title":"Dev","hiringOrganization":{"name":"Globex"},"description":"Build stuff"}
                ]</script>
                """;

        assertThat(extractor.extract(html)).isPresent();
        assertThat(extractor.extract(html).get().title()).isEqualTo("Dev");
    }

    @Test
    void extractsFromGraph() {
        String html = """
                <script type="application/ld+json">{
                  "@graph": [
                    {"@type":"JobPosting","title":"Engineer","hiringOrganization":"Initech","description":"Code"}
                  ]
                }</script>
                """;

        assertThat(extractor.extract(html)).isPresent();
        assertThat(extractor.extract(html).get().employer()).isEqualTo("Initech");
    }

    @Test
    void ignoresMalformedBlocks() {
        String html = """
                <script type="application/ld+json">{ not json }</script>
                <script type="application/ld+json">{"@type":"JobPosting","description":""}</script>
                """;

        assertThat(extractor.extract(html)).isEmpty();
    }
}
