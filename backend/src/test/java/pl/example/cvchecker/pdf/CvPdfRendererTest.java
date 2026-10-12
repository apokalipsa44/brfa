package pl.example.cvchecker.pdf;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.example.cvchecker.llm.CvDocument;
import pl.example.cvchecker.llm.EducationEntry;
import pl.example.cvchecker.llm.ExperienceEntry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 9 acceptance: the generated PDF has selectable text (not an image) and renders the full
 * Polish character set (ąęłńóśźż) when extracted back with PDFBox.
 */
class CvPdfRendererTest {

    private final CvPdfRenderer renderer = new CvPdfRenderer();
    private final PdfTextExtractor extractor = new PdfTextExtractor();

    @TempDir
    Path temp;

    @Test
    void rendersSelectableTextWithPolishCharacters() throws Exception {
        CvDocument document = new CvDocument("Żaneta Ćwikła", "zaneta@example.com", "+48 600 000 000",
                "Warszawa", "Doświadczona programistka Java. Znajomość: ąćęłńóśźż.",
                List.of(new ExperienceEntry("Backend Developer", "Acme", "2020-2025",
                        List.of("Budowała usługi w Spring Boot", "Pracowała z PostgreSQL"))),
                List.of(new EducationEntry("Politechnika Warszawska", "MSc Informatyka", "2015-2020")),
                List.of("Java", "Spring", "Docker"), List.of("polski", "English"), List.of("OCP Java"));

        Path out = temp.resolve("cv.pdf");
        renderer.render(document, out);

        assertThat(out).exists();
        String text = extractor.extract(out);

        assertThat(text).contains("Żaneta Ćwikła");
        assertThat(text).contains("ąćęłńóśźż");
        assertThat(text).contains("Backend Developer");
        assertThat(text).contains("Politechnika Warszawska");
        assertThat(text).contains("Experience", "Education", "Skills", "Languages");
    }
}
