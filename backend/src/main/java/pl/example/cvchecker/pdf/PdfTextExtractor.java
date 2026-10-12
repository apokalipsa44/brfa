package pl.example.cvchecker.pdf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * Extracts text from an uploaded CV PDF (plan §6.7 step 1).
 * A PDF has no paragraph structure, so this returns plain text for the LLM;
 * the improved CV is later rendered from scratch (plan §1).
 */
@Component
public class PdfTextExtractor {

    /**
     * @throws PdfNoTextException when nothing extractable is found (scan)
     * @throws PdfUnreadableException when the file is encrypted or corrupted
     */
    public String extract(Path pdf) {
        try {
            return extract(Files.readAllBytes(pdf));
        } catch (IOException e) {
            throw new PdfUnreadableException("Could not read the uploaded PDF.", e);
        }
    }

    /**
     * Extracts text from raw PDF bytes — used at upload time so a scan is rejected before it ever
     * becomes the active CV (docs/API.md §2).
     *
     * @throws PdfNoTextException when nothing extractable is found (scan)
     * @throws PdfUnreadableException when the file is encrypted or corrupted
     */
    public String extract(byte[] data) {
        try (PDDocument document = Loader.loadPDF(data)) {
            String text = new PDFTextStripper().getText(document);
            if (text == null || text.isBlank()) {
                throw new PdfNoTextException("No text found in the PDF (it may be a scan).");
            }
            return text.strip();
        } catch (PdfNoTextException e) {
            throw e;
        } catch (IOException e) {
            throw new PdfUnreadableException("The PDF is encrypted or corrupted.", e);
        }
    }
}
