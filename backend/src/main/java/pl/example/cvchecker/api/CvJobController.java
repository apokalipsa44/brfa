package pl.example.cvchecker.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import pl.example.cvchecker.job.CvJob;
import pl.example.cvchecker.job.CvJobRegistry;
import pl.example.cvchecker.job.Stage;
import pl.example.cvchecker.pipeline.SimulatedPipeline;
import pl.example.cvchecker.sse.SseHub;
import pl.example.cvchecker.storage.TempFileStorage;

@RestController
@RequestMapping("/api/cv-jobs")
public class CvJobController {

    static final long MAX_FILE_BYTES = 5L * 1024 * 1024; // mirrors spring.servlet.multipart.max-file-size
    static final int MAX_DESCRIPTION_CHARS = 20_000;

    private final CvJobRegistry registry;
    private final TempFileStorage storage;
    private final SseHub hub;
    private final SimulatedPipeline pipeline;

    public CvJobController(CvJobRegistry registry, TempFileStorage storage, SseHub hub, SimulatedPipeline pipeline) {
        this.registry = registry;
        this.storage = storage;
        this.hub = hub;
        this.pipeline = pipeline;
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
        registry.create(job);
        pipeline.start(job); // emits QUEUED and runs the stages asynchronously (plan §6.7)

        return ResponseEntity.accepted().body(new CreatedJobResponse(jobId, Stage.QUEUED));
    }

    /** SSE stream of job events (plan §6.6): replay from Last-Event-ID, then live events. */
    @GetMapping(value = "/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable String jobId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        CvJob job = registry.find(jobId).orElseThrow(() -> new ApiException(404, "JOB_NOT_FOUND",
                "Job not found", "No CV job with this id (it may have expired)."));
        return hub.subscribe(job, parseLastEventId(lastEventId));
    }

    private static long parseLastEventId(String header) {
        if (header == null || header.isBlank()) return 0L;
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return 0L; // malformed id: replay from the beginning
        }
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
