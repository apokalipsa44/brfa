package pl.example.cvchecker.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import pl.example.cvchecker.job.CvJob;
import pl.example.cvchecker.job.CvJobRegistry;
import pl.example.cvchecker.job.Stage;
import pl.example.cvchecker.storage.TempFileStorage;

@RestController
@RequestMapping("/api/cv-jobs")
public class CvJobController {

    static final long MAX_FILE_BYTES = 5L * 1024 * 1024; // mirrors spring.servlet.multipart.max-file-size
    static final int MAX_DESCRIPTION_CHARS = 20_000;

    private final CvJobRegistry registry;
    private final TempFileStorage storage;

    public CvJobController(CvJobRegistry registry, TempFileStorage storage) {
        this.registry = registry;
        this.storage = storage;
    }

    /** Creates a job and returns immediately (202); processing happens asynchronously later. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CreatedJobResponse> create(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "jobDescription", required = false) String jobDescription) {

        if (file == null || file.isEmpty()) {
            throw new ApiException(400, "MISSING_FILE", "File missing",
                    "The 'file' part with the CV PDF is required.");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ApiException(413, "FILE_TOO_LARGE", "File too large",
                    "The PDF is larger than 5 MB (" + file.getSize() + " bytes).");
        }
        validatePdf(file);

        if (jobDescription == null || jobDescription.isBlank()) {
            throw new ApiException(400, "MISSING_JOB_DESCRIPTION", "Job description missing",
                    "The 'jobDescription' part with the job posting text is required.");
        }
        if (jobDescription.length() > MAX_DESCRIPTION_CHARS) {
            throw new ApiException(400, "JOB_DESCRIPTION_TOO_LONG", "Job description too long",
                    "The job description has " + jobDescription.length() + " characters; the limit is 20000.");
        }

        String jobId = UUID.randomUUID().toString();
        Path originalFile;
        try {
            originalFile = storage.storeOriginal(jobId, file.getBytes());
        } catch (IOException e) {
            throw new ApiException(500, "INTERNAL_ERROR", "Internal error", "Could not store the uploaded file.");
        }

        CvJob job = new CvJob(jobId, jobDescription, originalFile);
        job.appendStatus(Stage.QUEUED, "Queued for processing", 0);
        registry.create(job);

        return ResponseEntity.accepted().body(new CreatedJobResponse(jobId, Stage.QUEUED));
    }

    /** Content-Type alone is not trusted: the %PDF- signature must be present (plan §6.5). */
    private void validatePdf(MultipartFile file) {
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
