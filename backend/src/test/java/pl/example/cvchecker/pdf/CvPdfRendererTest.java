package pl.example.cvchecker.pdf;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.example.cvchecker.llm.CvDocument;
import pl.example.cvchecker.llm.EducationEntry;
import pl.example.cvchecker.llm.ExperienceEntry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the new layout: full section headings in order, dates on the job-title line, Polish
 * characters, and multi-page rendering without text outside the margins.
 */
class CvPdfRendererTest {

    private final CvPdfRenderer renderer = new CvPdfRenderer();
    private final PdfTextExtractor extractor = new PdfTextExtractor();

    @TempDir
    Path temp;

    @Test
    void rendersSelectableTextWithPolishCharacters() throws Exception {
        CvDocument document = new CvDocument("Żaneta Ćwikła", null, "zaneta@example.com", "+48 600 000 000",
                "Warszawa", List.of(),
                "Doświadczona programistka Java. Znajomość: ąćęłńóśźż.",
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
        assertThat(text).contains("WORK EXPERIENCE", "EDUCATION", "SKILLS", "LANGUAGES");
    }

    @Test
    void rendersNewLayoutHeadingsOrderAndDatesOnTitleLine() throws Exception {
        List<ExperienceEntry> experience = new ArrayList<>();
        experience.add(new ExperienceEntry("Senior Java Developer", "TechCorp", "2023-2026",
                List.of("Designed microservices", "Optimized performance")));
        experience.add(new ExperienceEntry(
                "Full Stack Engineer with a very long job title to exercise the date fallback path that wraps "
                        + "onto the next line when the combined width exceeds the content area",
                "BigCompany", "2020-2023", List.of("Built Angular apps", "Worked with SQL")));

        CvDocument document = new CvDocument("Michał Łączny", "Full Stack Software Developer",
                "michal@example.com", "+48 123 456 789", "Wrocław",
                List.of("linkedin.com/in/michal", "github.com/michal"),
                "Experienced full-stack developer focused on clean code", experience,
                List.of(new EducationEntry("Wrocław University of Science and Technology", "MSc Computer Science",
                        "2016-2020")),
                List.of("Frontend: Angular, TypeScript, Tailwind", "Backend: Java, Spring Boot, PostgreSQL",
                        "Tools: Git, Docker, Maven"),
                List.of("Polish: Native", "English: C1"),
                List.of("Oracle Certified Professional"));

        Path out = temp.resolve("cv_new.pdf");
        renderer.render(document, out);

        assertThat(out).exists();
        String text = extractor.extract(out);

        assertThat(text).contains("Michał Łączny");
        assertThat(text).contains("Wrocław");

        // required standard headings, in reading order
        int summary = indexOfHeading(text, "PROFESSIONAL SUMMARY");
        int experienceH = indexOfHeading(text, "WORK EXPERIENCE");
        int education = indexOfHeading(text, "EDUCATION");
        int skills = indexOfHeading(text, "SKILLS");
        int languages = indexOfHeading(text, "LANGUAGES");
        assertThat(indexOfHeading(text, "CERTIFICATIONS")).isGreaterThan(languages);
        assertThat(experienceH).isGreaterThan(summary);
        assertThat(education).isGreaterThan(experienceH);
        assertThat(skills).isGreaterThan(education);
        assertThat(languages).isGreaterThan(skills);

        // dates on the same line as the (short) job title
        assertThat(text).contains("Senior Java Developer");
        assertThat(text).contains("2023-2026");
        assertThat(lineContaining(text, "Senior Java Developer")).contains("2023-2026");

        // skills rendered with their category labels
        assertThat(text).contains("Frontend:");
        assertThat(text).contains("Angular");

        // optional header extras: headline under the name, links in the contact line
        assertThat(text).contains("Full Stack Software Developer");
        assertThat(text).contains("linkedin.com/in/michal");
        assertThat(text).contains("github.com/michal");
        assertThat(lineContaining(text, "michal@example.com")).contains("linkedin.com/in/michal");

        // no GSUB ligatures in the raw stream: ATS parsers reading ToUnicode must see plain "fi"/"ff"
        LigatureScanner scan = scanLigatures(out);
        assertThat(scan.glyphs).as("glyphs scanned").isGreaterThan(0);
        assertThat(scan.ligatures).as("U+FB01-style ligatures in the stream").isEmpty();
    }

    @Test
    void rendersLongCvWithMultiplePagesAndNoTextOutsideMargins() throws Exception {
        List<ExperienceEntry> experience = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            experience.add(new ExperienceEntry("Software Engineer " + i, "Company " + i,
                    "201" + (i % 10) + "-202" + (i % 10),
                    List.of("Implemented feature " + i, "Improved system reliability",
                            "Collaborated with cross-functional team on delivery")));
        }

        List<EducationEntry> education = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            education.add(new EducationEntry("University " + i, "Degree " + i, "201" + i + "-201" + (i + 4)));
        }

        List<String> skills = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            skills.add("Category" + i + ": SkillA" + i + ", SkillB" + i + ", SkillC" + i);
        }

        CvDocument document = new CvDocument("Test User Long", "Software Engineer", "test@example.com", "123456",
                "City", List.of(), "Summary of a long CV for testing pagination", experience, education, skills,
                List.of("English", "German", "French"),
                List.of("Cert 1", "Cert 2", "Cert 3", "Cert 4", "Cert 5"));

        Path out = temp.resolve("cv_long.pdf");
        renderer.render(document, out);

        assertThat(out).exists();

        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(1);

            // every text position must stay inside the renderer's margins (50pt sides, 48pt top/bottom)
            MarginCapturingStripper capturer = new MarginCapturingStripper();
            capturer.setSortByPosition(true);
            capturer.writeText(pdf, new java.io.StringWriter());
            assertThat(capturer.runs).as("stripper saw text runs").isGreaterThan(0);
            assertThat(capturer.violations)
                    .as("text outside margins: %s", capturer.violations)
                    .isEmpty();

            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(pdf);
            assertThat(text).contains("Software Engineer 1");
            assertThat(text).contains("Software Engineer 30");
            assertThat(text).doesNotContain("�");
        }
    }

    @Test
    void compactsASparseOverflowBackOntoASinglePage() throws Exception {
        // At the loosest density this content spills a few lines onto a second page; the renderer
        // must compact spacing/size to keep the CV on ONE page rather than add a near-empty page.
        List<String> bullets = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            bullets.add("Delivered feature number " + i + " end to end with tests and reviews");
        }
        CvDocument document = new CvDocument("Jan Kowalski", "Java Developer", "jan@example.com",
                "+48 600 000 000", "Warszawa", List.of(),
                "Experienced backend developer building reliable distributed services with Java.",
                List.of(new ExperienceEntry("Backend Developer", "Acme", "2020-2025", bullets)),
                List.of(new EducationEntry("Politechnika", "MSc", "2015-2020")),
                List.of("Backend: Java, Spring Boot, PostgreSQL, Kafka", "Tools: Git, Docker, Kubernetes"),
                List.of("Polish: Native", "English: C1"), List.of("OCP Java"));

        Path out = temp.resolve("cv_fit.pdf");
        renderer.render(document, out);

        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
        }
        // no content is dropped to achieve the fit
        assertThat(extractor.extract(out)).contains("Delivered feature number 23");
    }

    /** Captures every rendered text run's page coordinates and reports margin violations. */
    private static final class MarginCapturingStripper extends PDFTextStripper {
        final List<String> violations = new ArrayList<>();
        int runs;

        MarginCapturingStripper() throws java.io.IOException {
        }

        @Override
        protected void writeString(String string, List<org.apache.pdfbox.text.TextPosition> positions)
                throws java.io.IOException {
            if (!positions.isEmpty()) {
                runs++;
                org.apache.pdfbox.text.TextPosition first = positions.get(0);
                org.apache.pdfbox.text.TextPosition last = positions.get(positions.size() - 1);
                float pageWidth = getCurrentPage().getMediaBox().getWidth();
                float pageHeight = getCurrentPage().getMediaBox().getHeight();
                float left = first.getXDirAdj();
                float right = last.getXDirAdj() + last.getWidthDirAdj();
                // TextPosition y is measured from the top; baselines sit slightly below glyphs.
                float top = first.getYDirAdj() - first.getHeightDir();
                float bottom = last.getYDirAdj();
                if (left < 49f || right > pageWidth - 49f) {
                    violations.add("horizontal [" + left + "," + right + "] of '" + string.trim() + "'");
                }
                if (top < 47f || bottom > pageHeight - 47f) {
                    violations.add("vertical [" + top + "," + bottom + "] of '" + string.trim() + "'");
                }
            }
            super.writeString(string, positions);
        }
    }

    /** Collects every glyph whose ToUnicode entry is a Unicode ligature (U+FB00..U+FB06). */
    private static final class LigatureScanner extends PDFTextStripper {
        final List<String> ligatures = new ArrayList<>();
        int glyphs;

        @Override
        protected void processTextPosition(org.apache.pdfbox.text.TextPosition textPosition) {
            glyphs++;
            String unicode = textPosition.getUnicode();
            if (unicode != null && unicode.length() == 1
                    && unicode.charAt(0) >= '\uFB00' && unicode.charAt(0) <= '\uFB06') {
                ligatures.add(unicode);
            }
            super.processTextPosition(textPosition);
        }
    }

    private static LigatureScanner scanLigatures(Path pdf) throws java.io.IOException {
        LigatureScanner scanner = new LigatureScanner();
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            scanner.getText(document);
        }
        return scanner;
    }

    private static int indexOfHeading(String text, String heading) {
        int idx = text.indexOf(heading);
        assertThat(idx).as("heading %s present", heading).isGreaterThanOrEqualTo(0);
        return idx;
    }

    private static String lineContaining(String text, String needle) {
        for (String line : text.split("\n")) {
            if (line.contains(needle)) return line;
        }
        throw new AssertionError("No line contains: " + needle);
    }
}
