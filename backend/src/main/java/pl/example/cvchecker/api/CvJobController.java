package pl.example.cvchecker.api;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import pl.example.cvchecker.job.CvJob;
import pl.example.cvchecker.job.CvJobRegistry;
import pl.example.cvchecker.job.Stage;
import pl.example.cvchecker.pipeline.CvProcessingPipeline;
import pl.example.cvchecker.sse.SseHub;
import pl.example.cvchecker.storage.CvContext;
import pl.example.cvchecker.storage.CvContextStore;
import pl.example.cvchecker.storage.TempFileStorage;

@RestController
@RequestMapping("/api/cv-jobs")
public class CvJobController {

    private static final Logger log = LoggerFactory.getLogger(CvJobController.class);

    static final int MAX_DESCRIPTION_CHARS = 20_000;

    private final CvJobRegistry registry;
    private final TempFileStorage storage;
    private final SseHub hub;
    private final CvProcessingPipeline pipeline;
    private final CvContextStore cvStore;

    public CvJobController(CvJobRegistry registry, TempFileStorage storage, SseHub hub,
            CvProcessingPipeline pipeline, CvContextStore cvStore) {
        this.registry = registry;
        this.storage = storage;
        this.hub = hub;
        this.pipeline = pipeline;
        this.cvStore = cvStore;
    }

    /**
     * Creates a job from the active workspace CV (docs/API.md §2) and returns immediately (202); the
     * PDF itself is not part of the request — the CV is uploaded once and reused for every posting.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CreatedJobResponse> create(
            @RequestParam(value = "cvId", required = false) String cvId,
            @RequestParam(value = "jobDescription", required = false) String jobDescription) {

        CvContext cv = cvStore.current().orElseThrow(() -> new ApiException(404, "CV_NOT_FOUND",
                "CV not found", "No active CV. Upload the CV first (POST /api/cv)."));

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
            // The job owns its copy, so replacing the stored CV never breaks a queued/running job.
            originalFile = storage.storeOriginal(jobId, Files.readAllBytes(cv.path()));
        } catch (IOException e) {
            throw new ApiException(500, "INTERNAL_ERROR", "Internal error", "Could not store the CV file.");
        }

        CvJob job = new CvJob(jobId, cv.fileName(), jobDescription, originalFile, null);
        registry.create(job);
        // Never log CV/posting content (AGENTS.md): only the job id.
        log.info("Created job {} with the configured default model", jobId);
        pipeline.start(job); // emits QUEUED and runs the stages asynchronously (plan §6.7)

        return ResponseEntity.accepted().body(new CreatedJobResponse(jobId, Stage.QUEUED));
    }

    /** The recent job history (newest first) used to restore the list after a page refresh (docs/API.md §8). */
    @GetMapping
    public List<JobSnapshot> list() {
        return registry.findAll().stream()
                .sorted(Comparator.comparing(CvJob::createdAt).reversed())
                .map(CvJobController::toSnapshot)
                .toList();
    }

    /** State snapshot used to restore the UI after a page refresh (docs/API.md §2). */
    @GetMapping("/{jobId}")
    public JobSnapshot snapshot(@PathVariable String jobId) {
        CvJob job = registry.find(jobId).orElseThrow(() -> new ApiException(404, "JOB_NOT_FOUND",
                "Job not found", "No CV job with this id (it may have expired)."));
        return toSnapshot(job);
    }

    /** Removes a job from the history (the Dismiss action on the job card, docs/API.md §8). */
    @DeleteMapping("/{jobId}")
    public ResponseEntity<Void> delete(@PathVariable String jobId) {
        if (!registry.remove(jobId)) {
            throw new ApiException(404, "JOB_NOT_FOUND", "Job not found", "No CV job with this id.");
        }
        return ResponseEntity.noContent().build();
    }

    private static JobSnapshot toSnapshot(CvJob job) {
        boolean completed = job.stage() == Stage.COMPLETED;
        boolean resultAvailable = completed && job.resultFile() != null && Files.exists(job.resultFile());
        return new JobSnapshot(job.id(), job.fileName(), job.stage(), job.createdAt(), job.progress(),
                resultAvailable ? "/api/cv-jobs/" + job.id() + "/result" : null,
                completed ? job.report() : null,
                job.errorCode(), job.errorMessage(), job.model());
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

    /** Downloads the result PDF once the job is COMPLETED. */
    @GetMapping("/{jobId}/result")
    public ResponseEntity<Resource> result(@PathVariable String jobId) {
        CvJob job = registry.find(jobId).orElseThrow(() -> new ApiException(404, "JOB_NOT_FOUND",
                "Job not found", "No CV job with this id (it may have expired)."));
        if (job.stage() != Stage.COMPLETED) {
            throw new ApiException(409, "NOT_READY", "Job not completed",
                    "The job is still processing; wait for the COMPLETED event.");
        }
        Path file = job.resultFile();
        if (file == null || !Files.exists(file)) {
            throw new ApiException(404, "RESULT_NOT_FOUND", "Result file missing",
                    "The result file is not available (it may have been cleaned up).");
        }
        String fileName = URLEncoder.encode("cv-improved-" + jobId + ".pdf", StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename*=UTF-8''" + fileName)
                .contentType(MediaType.APPLICATION_PDF)
                .body(new FileSystemResource(file));
    }

    private static long parseLastEventId(String header) {
        if (header == null || header.isBlank()) return 0L;
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return 0L; // malformed id: replay from the beginning
        }
    }
}
