package pl.example.cvchecker.api;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import pl.example.cvchecker.pdf.PdfNoTextException;
import pl.example.cvchecker.pdf.PdfTextExtractor;
import pl.example.cvchecker.pdf.PdfUnreadableException;
import pl.example.cvchecker.storage.CvContext;
import pl.example.cvchecker.storage.CvContextStore;

/**
 * The single active workspace CV (docs/API.md §2): uploaded once, text extracted immediately, and
 * reused as the context for every analysis and the old job flow. Replacing it (upload again) only
 * affects future analyses — each analysis snapshots its own copy of the text at creation.
 */
@RestController
@RequestMapping("/api/cv")
public class CvController {

    /** Mirrors spring.servlet.multipart.max-file-size (application.yml). */
    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    private final CvContextStore store;
    private final PdfTextExtractor extractor;

    public CvController(CvContextStore store, PdfTextExtractor extractor) {
        this.store = store;
        this.extractor = extractor;
    }

    /**
     * Stores the uploaded PDF as the active CV. Text is extracted immediately: a scanned or
     * damaged PDF is rejected ({@code 422}) and the previous CV stays active (docs/API.md §2).
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CvContextResponse> upload(
            @RequestParam(value = "file", required = false) MultipartFile file) throws IOException {
        validatePdf(file);
        byte[] data = file.getBytes();
        final String text;
        try {
            text = extractor.extract(data);
        } catch (PdfNoTextException e) {
            throw new ApiException(422, "PDF_NO_TEXT", "Scanned PDF",
                    "The PDF has no extractable text (it might be a scan). OCR is out of scope.");
        } catch (PdfUnreadableException e) {
            throw new ApiException(422, "PDF_UNREADABLE", "PDF unreadable",
                    "The PDF is encrypted or corrupted and its text could not be read.");
        }
        String fileName = file.getOriginalFilename();
        CvContext cv = store.place(fileName == null || fileName.isBlank() ? "cv.pdf" : fileName, data, text);
        return ResponseEntity.created(URI.create("/api/cv/" + cv.id())).body(toResponse(cv));
    }

    /** Metadata of the active CV — used to restore the selection after a page refresh (docs/API.md §2). */
    @GetMapping
    public CvContextResponse current() {
        CvContext cv = store.current().orElseThrow(() -> new ApiException(404, "CV_NOT_FOUND",
                "CV not found", "No CV uploaded yet, or the server restarted. Upload one to start."));
        return toResponse(cv);
    }

    private static CvContextResponse toResponse(CvContext cv) {
        return new CvContextResponse(cv.id(), cv.fileName(), "READY");
    }

    /** Content-Type alone is not trusted: the %PDF- signature must be present (plan §6.5). */
    private static void validatePdf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(400, "MISSING_FILE", "File missing",
                    "The 'file' part with the CV PDF is required.");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ApiException(413, "FILE_TOO_LARGE", "File too large",
                    "The PDF is larger than 5 MB (" + file.getSize() + " bytes).");
        }
        if (!"application/pdf".equalsIgnoreCase(file.getContentType())) {
            throw new ApiException(415, "INVALID_PDF", "Not a PDF",
                    "The 'file' part must have Content-Type application/pdf.");
        }
        try (var in = file.getInputStream()) {
            byte[] head = in.readNBytes(1024);
            if (new String(head, StandardCharsets.ISO_8859_1).indexOf("%PDF-") < 0) {
                throw new ApiException(415, "INVALID_PDF", "Not a PDF",
                        "The file does not contain the %PDF- signature.");
            }
        } catch (IOException e) {
            throw new ApiException(415, "INVALID_PDF", "Not a PDF", "Could not read the uploaded file.");
        }
    }
}