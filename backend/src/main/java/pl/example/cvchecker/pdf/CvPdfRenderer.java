package pl.example.cvchecker.pdf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

import pl.example.cvchecker.llm.CvDocument;
import pl.example.cvchecker.llm.EducationEntry;
import pl.example.cvchecker.llm.ExperienceEntry;

/**
 * Renders the improved CV to a new ATS-friendly PDF (plan §1, §6.7 step 4, §6.8): single column,
 * selectable text, standard headings, no tables/images. Uses PDFBox 3 directly with an embedded
 * DejaVu Sans font, which covers the full Polish character set (ąęłńóśźż).
 *
 * <p>Decision: openhtmltopdf 1.0.10 depends on PDFBox 2.x, which conflicts with the PDFBox 3.x used
 * for extraction (see docs/DECISIONS.md), so the template is rendered directly with PDFBox 3.
 */
@Component
public class CvPdfRenderer {

    private static final float MARGIN = 50f;
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN;
    private static final float BODY_SIZE = 10.5f;
    private static final float BODY_LEADING = 14f;
    private static final float HEADING_SIZE = 13f;
    private static final float NAME_SIZE = 20f;
    private static final float BULLET_INDENT = 12f;

    private final byte[] regularFont;
    private final byte[] boldFont;

    public CvPdfRenderer() {
        this.regularFont = readFont("fonts/DejaVuSans.ttf");
        this.boldFont = readFont("fonts/DejaVuSans-Bold.ttf");
    }

    /** Renders {@code document} to {@code target} as a selectable-text PDF. */
    public void render(CvDocument document, Path target) throws IOException {
        try (PDDocument pdf = new PDDocument()) {
            PDType0Font regular = PDType0Font.load(pdf, new ByteArrayInputStream(regularFont), true);
            PDType0Font bold = PDType0Font.load(pdf, new ByteArrayInputStream(boldFont), true);
            Writer writer = new Writer(pdf, regular, bold);
            writeDocument(writer, document);
            writer.close();
            pdf.save(target.toFile());
        }
    }

    private void writeDocument(Writer writer, CvDocument doc) throws IOException {
        writer.heading(clean(doc.fullName()), NAME_SIZE, 24f);

        String contact = joinNonBlank(" | ", doc.email(), doc.phone(), doc.location());
        if (!contact.isBlank()) writer.paragraph(contact, 7f);

        section(writer, "Summary", doc.summary());

        if (doc.experience() != null && !doc.experience().isEmpty()) {
            writer.heading("Experience", HEADING_SIZE, 8f);
            for (ExperienceEntry entry : doc.experience()) {
                String header = joinNonBlank(" — ", entry.title(), entry.company());
                if (entry.dates() != null && !entry.dates().isBlank()) {
                    header = header.isBlank() ? entry.dates() : header + " (" + entry.dates() + ")";
                }
                writer.paragraph(header, 4f);
                writer.bullets(entry.bullets());
                writer.paragraph("", 6f);
            }
        }

        if (doc.education() != null && !doc.education().isEmpty()) {
            writer.heading("Education", HEADING_SIZE, 8f);
            for (EducationEntry entry : doc.education()) {
                String header = joinNonBlank(" — ", entry.degree(), entry.school());
                if (entry.dates() != null && !entry.dates().isBlank()) {
                    header = header.isBlank() ? entry.dates() : header + " (" + entry.dates() + ")";
                }
                writer.paragraph(header, 4f);
            }
        }

        section(writer, "Skills", doc.skills());
        section(writer, "Languages", doc.languages());
        section(writer, "Certifications", doc.certifications());
    }

    private void section(Writer writer, String title, String body) throws IOException {
        if (body == null || body.isBlank()) return;
        writer.heading(title, HEADING_SIZE, 8f);
        writer.paragraph(body, 6f);
    }

    private void section(Writer writer, String title, List<String> items) throws IOException {
        if (items == null || items.isEmpty()) return;
        writer.heading(title, HEADING_SIZE, 8f);
        writer.bullets(items);
        writer.paragraph("", 6f);
    }

    private static String joinNonBlank(String sep, String... parts) {
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) values.add(part.strip());
        }
        return String.join(sep, values);
    }

    /** Strips control characters PDFBox cannot encode; keeps Polish letters. */
    private static String clean(String text) {
        if (text == null) return "";
        return text.replace("\t", "  ").replaceAll("[\\p{Cntrl}&&[^\\n]]", "").strip();
    }

    private static byte[] readFont(String resource) {
        try (InputStream in = CvPdfRenderer.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Missing embedded font: " + resource);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load embedded font: " + resource, e);
        }
    }

    /** Stateful page/cursor writer: starts a new page when the cursor reaches the bottom margin. */
    private static final class Writer implements AutoCloseable {

        private final PDDocument pdf;
        private final PDType0Font regular;
        private final PDType0Font bold;
        private PDPageContentStream stream;
        private float y;

        Writer(PDDocument pdf, PDType0Font regular, PDType0Font bold) throws IOException {
            this.pdf = pdf;
            this.regular = regular;
            this.bold = bold;
            newPage();
        }

        private void newPage() throws IOException {
            if (stream != null) stream.close();
            PDPage page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);
            stream = new PDPageContentStream(pdf, page);
            y = PAGE_HEIGHT - MARGIN;
        }

        private void ensure(float needed) throws IOException {
            if (y - needed < MARGIN) newPage();
        }

        void heading(String text, float size, float spaceBefore) throws IOException {
            if (text == null || text.isBlank()) return;
            ensure(size + spaceBefore + BODY_LEADING);
            y -= spaceBefore;
            writeLine(text, bold, size);
        }

        void paragraph(String text, float spaceAfter) throws IOException {
            if (text == null || text.isBlank()) {
                y -= spaceAfter;
                return;
            }
            for (String line : wrap(clean(text), regular, BODY_SIZE, CONTENT_WIDTH)) {
                ensure(BODY_LEADING);
                writeLine(line, regular, BODY_SIZE);
            }
            y -= spaceAfter;
        }

        void bullets(List<String> items) throws IOException {
            if (items == null) return;
            for (String item : items) {
                if (item == null || item.isBlank()) continue;
                List<String> lines = wrap(clean(item), regular, BODY_SIZE, CONTENT_WIDTH - BULLET_INDENT);
                for (int i = 0; i < lines.size(); i++) {
                    ensure(BODY_LEADING);
                    y -= BODY_LEADING;
                    if (i == 0) {
                        stream.beginText();
                        stream.setFont(regular, BODY_SIZE);
                        stream.newLineAtOffset(MARGIN, y);
                        stream.showText("•");
                        stream.endText();
                    }
                    writeLineAt(lines.get(i), regular, BODY_SIZE, MARGIN + BULLET_INDENT);
                }
            }
        }

        private void writeLine(String text, PDFont font, float size) throws IOException {
            y -= BODY_LEADING;
            writeLineAt(text, font, size, MARGIN);
        }

        private void writeLineAt(String text, PDFont font, float size, float x) throws IOException {
            stream.beginText();
            stream.setFont(font, size);
            stream.newLineAtOffset(x, y);
            stream.showText(text);
            stream.endText();
        }

        private static List<String> wrap(String text, PDFont font, float size, float maxWidth) throws IOException {
            List<String> lines = new ArrayList<>();
            for (String rawLine : text.split("\n")) {
                StringBuilder current = new StringBuilder();
                for (String word : rawLine.strip().split("\\s+")) {
                    if (word.isEmpty()) continue;
                    String candidate = current.isEmpty() ? word : current + " " + word;
                    if (width(candidate, font, size) <= maxWidth) {
                        current.setLength(0);
                        current.append(candidate);
                    } else {
                        if (!current.isEmpty()) lines.add(current.toString());
                        current.setLength(0);
                        current.append(word);
                    }
                }
                lines.add(current.toString());
            }
            if (lines.isEmpty()) lines.add("");
            return lines;
        }

        private static float width(String text, PDFont font, float size) throws IOException {
            return font.getStringWidth(text) / 1000f * size;
        }

        @Override
        public void close() throws IOException {
            if (stream != null) stream.close();
        }
    }
}
