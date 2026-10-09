package pl.example.cvchecker.pdf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Milestone 6 acceptance: correct text from a PDF, PDF_NO_TEXT for a scan-like file. */
class PdfTextExtractorTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor();

    @TempDir
    Path temp;

    @Test
    void extractsTextFromPdf() throws Exception {
        Path pdf = temp.resolve("cv.pdf");
        writePdf(pdf, "Jan Kowalski", "Senior Java Developer", "5 years of Spring experience");

        assertThat(extractor.extract(pdf)).contains("Jan Kowalski", "Senior Java Developer", "Spring");
    }

    @Test
    void blankPageThrowsNoText() throws Exception {
        Path pdf = temp.resolve("blank.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }

        assertThatThrownBy(() -> extractor.extract(pdf)).isInstanceOf(PdfNoTextException.class);
    }

    @Test
    void garbageBytesThrowUnreadable() throws Exception {
        Path pdf = temp.resolve("garbage.pdf");
        Files.write(pdf, "this is not a pdf at all".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> extractor.extract(pdf)).isInstanceOf(PdfUnreadableException.class);
    }

    @Test
    void encryptedPdfThrowsUnreadable() throws Exception {
        Path pdf = temp.resolve("encrypted.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText("Secret CV content");
                stream.endText();
            }
            document.protect(new StandardProtectionPolicy("owner-pass", "user-pass", new AccessPermission()));
            document.save(pdf.toFile());
        }

        assertThatThrownBy(() -> extractor.extract(pdf)).isInstanceOf(PdfUnreadableException.class);
    }

    private static void writePdf(Path target, String... lines) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.setLeading(16);
                stream.newLineAtOffset(50, 700);
                for (String line : lines) {
                    stream.showText(line);
                    stream.newLine();
                }
                stream.endText();
            }
            document.save(target.toFile());
        }
    }
}
