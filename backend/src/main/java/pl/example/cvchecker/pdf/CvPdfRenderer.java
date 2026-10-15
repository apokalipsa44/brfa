package pl.example.cvchecker.pdf;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
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
 * Renders the improved CV to a new ATS-friendly PDF: single column, real selectable text in
 * natural reading order, standard section headings, no tables/images/text boxes. The only
 * graphics are thin horizontal rules under headings (ignored by parsers).
 *
 * <p>Layout: centered name + contact line, accent-coloured UPPERCASE section headings with a rule,
 * job/education lines with the title in bold and dates right-aligned on the same line, bullets
 * with a hanging indent. Uses PDFBox 3 directly with an embedded DejaVu Sans font (full Polish
 * character set).
 */
@Component
public class CvPdfRenderer {

    private static final float MARGIN_X = 50f;
    private static final float MARGIN_TOP = 48f;
    private static final float MARGIN_BOTTOM = 48f;
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN_X;

    private static final float NAME_SIZE = 22f;
    private static final float CONTACT_SIZE = 9.5f;
    private static final float HEADING_SIZE = 11.5f;
    private static final float BODY_SIZE = 10f;
    private static final float BODY_LEADING = 13.5f;
    private static final float BULLET_INDENT = 14f;
    private static final float SECTION_GAP = 14f;
    private static final float ENTRY_GAP = 7f;

    private static final Color ACCENT = new Color(0x1F, 0x3A, 0x5F);
    private static final Color TEXT = new Color(0x22, 0x22, 0x22);
    private static final Color MUTED = new Color(0x55, 0x55, 0x55);

    private final byte[] regularFont;
    private final byte[] boldFont;

    public CvPdfRenderer() {
        this.regularFont = readFont("fonts/DejaVuSans.ttf");
        this.boldFont = readFont("fonts/DejaVuSans-Bold.ttf");
    }

    /** Renders {@code document} to {@code target} as a selectable-text PDF. */
    public void render(CvDocument document, Path target) throws IOException {
        try (PDDocument pdf = new PDDocument()) {
            PDDocumentInformation info = pdf.getDocumentInformation();
            String name = clean(document.fullName());
            info.setTitle(name.isBlank() ? "CV" : name + " - CV");
            if (!name.isBlank()) info.setAuthor(name);

            PDType0Font regular = PDType0Font.load(pdf, new ByteArrayInputStream(regularFont), true);
            PDType0Font bold = PDType0Font.load(pdf, new ByteArrayInputStream(boldFont), true);
            try (Writer writer = new Writer(pdf, regular, bold)) {
                writeDocument(writer, document);
            }
            pdf.save(target.toFile());
        }
    }

    private void writeDocument(Writer w, CvDocument doc) throws IOException {
        w.centered(clean(doc.fullName()), w.bold, NAME_SIZE, ACCENT, 6f);

        if (doc.headline() != null && !doc.headline().isBlank()) {
            w.centered(clean(doc.headline()), w.regular, 11f, MUTED, 2f);
        }

        String contact = joinNonBlank("  |  ", doc.email(), doc.phone(), doc.location(),
                String.join("  |  ", nonBlank(doc.links())));
        if (!contact.isBlank()) w.centered(contact, w.regular, CONTACT_SIZE, MUTED, 2f);

        if (doc.summary() != null && !doc.summary().isBlank()) {
            w.heading("Professional Summary");
            w.paragraph(doc.summary());
        }

        if (doc.experience() != null && !doc.experience().isEmpty()) {
            w.heading("Work Experience");
            for (ExperienceEntry e : doc.experience()) {
                w.entryHeader(e.title(), e.company(), e.dates());
                w.bullets(e.bullets());
                w.gap(ENTRY_GAP);
            }
        }

        if (doc.education() != null && !doc.education().isEmpty()) {
            w.heading("Education");
            for (EducationEntry e : doc.education()) {
                w.entryHeader(e.degree(), e.school(), e.dates());
                w.gap(3f);
            }
        }

        skillsSection(w, doc.skills());
        plainListSection(w, "Languages", doc.languages());
        bulletSection(w, "Certifications", doc.certifications());
    }

    private void skillsSection(Writer w, List<String> items) throws IOException {
        List<String> values = nonBlank(items);
        if (values.isEmpty()) return;
        w.heading("Skills");
        boolean anyLabel = values.stream().anyMatch(v -> labelEnd(v) > 0);
        if (anyLabel) {
            for (String v : values) w.labelledLine(v);
        } else {
            w.paragraph(String.join(", ", values));
        }
    }

    private void plainListSection(Writer w, String title, List<String> items) throws IOException {
        List<String> values = nonBlank(items);
        if (values.isEmpty()) return;
        w.heading(title);
        for (String v : values) w.paragraph(v);
    }

    private void bulletSection(Writer w, String title, List<String> items) throws IOException {
        List<String> values = nonBlank(items);
        if (values.isEmpty()) return;
        w.heading(title);
        w.bullets(values);
    }

    private static int labelEnd(String text) {
        int idx = text.indexOf(':');
        return (idx > 0 && idx <= 40) ? idx : -1;
    }

    private static List<String> nonBlank(List<String> items) {
        List<String> out = new ArrayList<>();
        if (items == null) return out;
        for (String item : items) {
            if (item != null && !item.isBlank()) out.add(clean(item));
        }
        return out;
    }

    private static String joinNonBlank(String sep, String... parts) {
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) values.add(clean(part));
        }
        return String.join(sep, values);
    }

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

    private static final class Writer implements AutoCloseable {

        private final PDDocument pdf;
        final PDType0Font regular;
        final PDType0Font bold;
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
            y = PAGE_HEIGHT - MARGIN_TOP;
        }

        private void ensure(float needed) throws IOException {
            if (y - needed < MARGIN_BOTTOM) newPage();
        }

        void gap(float amount) {
            y -= amount;
        }

        void centered(String text, PDFont font, float size, Color color, float spaceAfter) throws IOException {
            if (text == null || text.isBlank()) return;
            for (String line : wrap(text, font, size, CONTENT_WIDTH, CONTENT_WIDTH)) {
                ensure(size + 4f);
                y -= size + 3f;
                float x = MARGIN_X + (CONTENT_WIDTH - width(line, font, size)) / 2f;
                drawText(line, font, size, x, color);
            }
            y -= spaceAfter;
        }

        void heading(String title) throws IOException {
            ensure(SECTION_GAP + HEADING_SIZE + 10f + 2 * BODY_LEADING);
            y -= SECTION_GAP;
            y -= HEADING_SIZE;
            drawText(title.toUpperCase(), bold, HEADING_SIZE, MARGIN_X, ACCENT);
            float ruleY = y - 4f;
            stream.setStrokingColor(ACCENT);
            stream.setLineWidth(0.8f);
            stream.moveTo(MARGIN_X, ruleY);
            stream.lineTo(MARGIN_X + CONTENT_WIDTH, ruleY);
            stream.stroke();
            y = ruleY - 5f;
        }

        void paragraph(String text) throws IOException {
            if (text == null || text.isBlank()) return;
            for (String line : wrap(clean(text), regular, BODY_SIZE, CONTENT_WIDTH, CONTENT_WIDTH)) {
                ensure(BODY_LEADING);
                y -= BODY_LEADING;
                drawText(line, regular, BODY_SIZE, MARGIN_X, TEXT);
            }
            y -= 2f;
        }

        void entryHeader(String title, String org, String dates) throws IOException {
            String t = clean(title);
            String o = clean(org);
            String d = clean(dates);
            if (t.isBlank() && o.isBlank() && d.isBlank()) return;

            ensure(BODY_LEADING * 2.5f);

            String sep = (!t.isBlank() && !o.isBlank()) ? " — " : "";
            float leftWidth = width(t, bold, BODY_SIZE) + width(sep + o, regular, BODY_SIZE);
            float dateWidth = d.isBlank() ? 0f : width(d, regular, BODY_SIZE);

            if (leftWidth + dateWidth + 12f <= CONTENT_WIDTH) {
                y -= BODY_LEADING;
                float x = MARGIN_X;
                if (!t.isBlank()) {
                    drawText(t, bold, BODY_SIZE, x, TEXT);
                    x += width(t, bold, BODY_SIZE);
                }
                if (!o.isBlank()) drawText(sep + o, regular, BODY_SIZE, x, TEXT);
                if (!d.isBlank()) {
                    drawText(d, regular, BODY_SIZE, MARGIN_X + CONTENT_WIDTH - dateWidth, MUTED);
                }
            } else {
                String joined = joinNonBlank(" — ", t, o);
                if (!d.isBlank()) joined = joined.isBlank() ? d : joined + " (" + d + ")";
                for (String line : wrap(joined, bold, BODY_SIZE, CONTENT_WIDTH, CONTENT_WIDTH)) {
                    ensure(BODY_LEADING);
                    y -= BODY_LEADING;
                    drawText(line, bold, BODY_SIZE, MARGIN_X, TEXT);
                }
            }
            y -= 2f;
        }

        void bullets(List<String> items) throws IOException {
            if (items == null) return;
            for (String item : items) {
                if (item == null || item.isBlank()) continue;
                List<String> lines = wrap(clean(item), regular, BODY_SIZE,
                        CONTENT_WIDTH - BULLET_INDENT, CONTENT_WIDTH - BULLET_INDENT);
                for (int i = 0; i < lines.size(); i++) {
                    ensure(BODY_LEADING);
                    y -= BODY_LEADING;
                    if (i == 0) drawText("•", regular, BODY_SIZE, MARGIN_X + 2f, ACCENT);
                    drawText(lines.get(i), regular, BODY_SIZE, MARGIN_X + BULLET_INDENT, TEXT);
                }
                y -= 1.5f;
            }
        }

        void labelledLine(String text) throws IOException {
            String clean = clean(text);
            int idx = labelEnd(clean);
            if (idx < 0) {
                paragraph(clean);
                return;
            }
            String label = clean.substring(0, idx + 1);
            String rest = clean.substring(idx + 1).strip();
            float labelWidth = width(label + " ", bold, BODY_SIZE);

            List<String> lines = wrap(rest, regular, BODY_SIZE, CONTENT_WIDTH - labelWidth, CONTENT_WIDTH);
            ensure(BODY_LEADING);
            y -= BODY_LEADING;
            drawText(label, bold, BODY_SIZE, MARGIN_X, TEXT);
            drawText(lines.get(0), regular, BODY_SIZE, MARGIN_X + labelWidth, TEXT);
            for (int i = 1; i < lines.size(); i++) {
                ensure(BODY_LEADING);
                y -= BODY_LEADING;
                drawText(lines.get(i), regular, BODY_SIZE, MARGIN_X, TEXT);
            }
            y -= 2f;
        }

        private void drawText(String text, PDFont font, float size, float x, Color color) throws IOException {
            if (text == null || text.isEmpty()) return;
            stream.beginText();
            stream.setFont(font, size);
            stream.setNonStrokingColor(color);
            stream.newLineAtOffset(x, y);
            // PDFBox applies GSUB ligatures ("fi" -> U+FB01) per showText call; ATS parsers that
            // read the ToUnicode map (poppler/pdfminer) would then extract "Certi<FB01>ed". Break
            // the string after every 'f' so no ligature forms while text stays selectable.
            int start = 0;
            for (int i = 1; i < text.length(); i++) {
                if (text.charAt(i - 1) == 'f') {
                    stream.showText(text.substring(start, i));
                    start = i;
                }
            }
            stream.showText(text.substring(start));
            stream.endText();
        }

        private static List<String> wrap(String text, PDFont font, float size, float firstWidth, float otherWidth)
                throws IOException {
            List<String> lines = new ArrayList<>();
            for (String rawLine : text.split("\n")) {
                StringBuilder current = new StringBuilder();
                for (String word : rawLine.strip().split("\\s+")) {
                    if (word.isEmpty()) continue;
                    float max = lines.isEmpty() ? firstWidth : otherWidth;
                    String candidate = current.isEmpty() ? word : current + " " + word;
                    if (width(candidate, font, size) <= max || current.isEmpty()) {
                        current.setLength(0);
                        current.append(candidate);
                    } else {
                        lines.add(current.toString());
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