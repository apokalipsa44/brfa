package pl.example.cvchecker.posting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static HTML text extraction (docs/API.md §4.1). Removes chrome elements and prefers main/article.
 */
class HtmlTextExtractorTest {

    private final HtmlTextExtractor extractor = new HtmlTextExtractor();

    @Test
    void removesChromeElements() {
        String html = """
                <html><body>
                <nav>Home</nav>
                <main>
                  <h1>Senior Java Developer</h1>
                  <p>We need a Java expert for Spring Boot projects.</p>
                </main>
                <footer>Copyright</footer>
                <script>alert(1)</script>
                </body></html>
                """;

        String text = extractor.extract(html);

        assertThat(text).contains("Senior Java Developer");
        assertThat(text).contains("Spring Boot");
        assertThat(text).doesNotContain("Home");
        assertThat(text).doesNotContain("Copyright");
        assertThat(text).doesNotContain("alert");
    }

    @Test
    void prefersArticleWhenNoMain() {
        String html = """
                <html><body>
                <article>
                  <h2>Job Title</h2>
                  <p>Responsibilities and requirements.</p>
                </article>
                </body></html>
                """;

        String text = extractor.extract(html);
        assertThat(text).contains("Job Title");
        assertThat(text).contains("Responsibilities");
    }
}
