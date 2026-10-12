package pl.example.cvchecker.pipeline;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import pl.example.cvchecker.job.CvJob;
import pl.example.cvchecker.job.CvJobRegistry;
import pl.example.cvchecker.job.JobReport;
import pl.example.cvchecker.job.Stage;
import pl.example.cvchecker.llm.CvAnalysis;
import pl.example.cvchecker.llm.CvAnalysisService;
import pl.example.cvchecker.llm.CvChange;
import pl.example.cvchecker.llm.CvDocument;
import pl.example.cvchecker.llm.CvRewriteResult;
import pl.example.cvchecker.llm.CvRewriteService;
import pl.example.cvchecker.llm.ExperienceEntry;
import pl.example.cvchecker.llm.Priority;
import pl.example.cvchecker.llm.Suggestion;
import pl.example.cvchecker.pdf.PdfTextExtractor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Milestone 10 acceptance with the LLM calls mocked out: the real pipeline must walk
 * EXTRACTING_TEXT → ANALYZING → REWRITING → GENERATING_PDF → COMPLETED, attach the report and a
 * downloadable result PDF whose text can be read back (only the LLM services are fake).
 */
@SpringBootTest(properties = "cvchecker.pipeline.mode=real")
class RealCvProcessingPipelineTest {

    /** Isolated job store so persisted history never leaks between test runs. */
    private static final Path JOB_STORE = newTempStore();

    @DynamicPropertySource
    static void jobStore(DynamicPropertyRegistry registry) {
        registry.add("cvchecker.jobs.store-path", () -> JOB_STORE.toString());
    }

    private static Path newTempStore() {
        try {
            return Files.createTempDirectory("cvchecker-store-").resolve("cv-jobs.json");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    CvProcessingPipeline pipeline;

    @Autowired
    CvJobRegistry registry;

    @Autowired
    PdfTextExtractor extractor;

    @MockitoBean
    CvAnalysisService analysisService;

    @MockitoBean
    CvRewriteService rewriteService;

    @Test
    void runsAllStagesAndCompletesWithResultPdf() throws Exception {
        Path original = tempPdf();
        CvJob job = new CvJob("pipeline-test-1", "Senior Java Developer wanted", original, "google/gemma-4-e4b");
        registry.create(job);

        when(analysisService.analyze(anyString(), anyString(), any())).thenReturn(analysis());
        when(rewriteService.rewrite(anyString(), anyString(), any(), any())).thenReturn(rewrite());

        pipeline.start(job);

        assertThat(await(() -> job.isTerminal())).isTrue();
        assertThat(job.stage()).isEqualTo(Stage.COMPLETED);
        assertThat(job.model()).isEqualTo("google/gemma-4-e4b");

        JobReport report = job.report();
        assertThat(report).isNotNull();
        assertThat(report.matchScoreBefore()).isEqualTo(58);
        assertThat(report.changes()).hasSize(1);

        assertThat(job.resultFile()).isNotNull();
        String text = extractor.extract(job.resultFile());
        assertThat(text).contains("Żaneta Ćwikła", "Backend Developer", "Java");
    }

    @Test
    void extractionFailureFailsJobWithPdfNoText() throws Exception {
        Path garbage = Path.of(System.getProperty("java.io.tmpdir"), "cvchecker-garbage-" + System.nanoTime() + ".pdf");
        java.nio.file.Files.writeString(garbage, "this is not a pdf");
        CvJob job = new CvJob("pipeline-test-2", "desc", garbage);
        registry.create(job);

        pipeline.start(job);

        assertThat(await(() -> job.isTerminal())).isTrue();
        assertThat(job.stage()).isEqualTo(Stage.FAILED);
        assertThat(job.errorCode()).isEqualTo("PDF_UNREADABLE");
    }

    private static CvAnalysis analysis() {
        return new CvAnalysis(58, List.of("Java", "Spring"), List.of("Kubernetes"),
                List.of(new Suggestion("Skills", "Missing K8s", "Add a K8s course", Priority.MEDIUM)),
                "Solid match, missing Kubernetes.");
    }

    private static CvRewriteResult rewrite() {
        CvDocument document = new CvDocument("Żaneta Ćwikła", "zaneta@example.com", "+48 600 000 000", "Warszawa",
                "Doświadczona programistka Java.",
                List.of(new ExperienceEntry("Backend Developer", "Acme", "2020-2025",
                        List.of("Budowała usługi w Spring Boot"))),
                List.of(), List.of("Java", "Spring"), List.of("polski"), List.of());
        return new CvRewriteResult(document, List.of(new CvChange("Summary", "old", "new", "Stronger opening")));
    }

    private static Path tempPdf() throws IOException {
        Path file = Path.of(System.getProperty("java.io.tmpdir"), "cvchecker-input-" + System.nanoTime() + ".pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText("Jan Kowalski, Senior Java Developer, 5 years of Spring experience.");
                stream.endText();
            }
            document.save(file.toFile());
        }
        return file;
    }

    private static boolean await(Supplier<Boolean> condition) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (condition.get()) return true;
            Thread.sleep(100);
        }
        return false;
    }
}