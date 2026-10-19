package pl.example.cvchecker.api;

import java.nio.file.Path;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import pl.example.cvchecker.cv.CvImprovementService;

/**
 * The standalone CV builder (docs/API.md §10): builds an ATS PDF from the active CV plus a
 * user-selected skill list, independent of the analysis flow. One current build at a time; the
 * state is broadcast over the global SSE stream as {@code cv.improvement.updated}.
 */
@RestController
@RequestMapping("/api/cv/improvement")
public class CvImprovementController {

    private final CvImprovementService service;

    public CvImprovementController(CvImprovementService service) {
        this.service = service;
    }

    /** Starts a build and returns the state (202, already {@code REWRITING}); 409 while one runs. */
    @PostMapping
    public ResponseEntity<CvImprovementState> start(@RequestBody(required = false) StartCvImprovementRequest body) {
        CvImprovementState state = service.start(body == null ? null : body.skills());
        return ResponseEntity.accepted().body(state);
    }

    /** The current build state (docs/API.md §10); {@code NONE} until the first build. */
    @GetMapping
    public CvImprovementState current() {
        return service.state();
    }

    /** Downloads the improved PDF once the build is COMPLETED (docs/API.md §10). */
    @GetMapping("/result")
    public ResponseEntity<Resource> result() {
        Path file = service.resultPath();
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"cv-improved.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(new FileSystemResource(file));
    }
}
