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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import pl.example.cvchecker.analysis.Analysis;
import pl.example.cvchecker.analysis.AnalysisPipeline;
import pl.example.cvchecker.analysis.AnalysisRegistry;
import pl.example.cvchecker.analysis.AnalysisStage;
import pl.example.cvchecker.llm.Evaluation;
import pl.example.cvchecker.llm.ModelContextService;
import pl.example.cvchecker.storage.CvContext;
import pl.example.cvchecker.storage.CvContextStore;

/**
 * Analyses (docs/API.md §4): one posting processed against the persistent CV + model context by a
 * two-stage pipeline paused at {@code AWAITING_REVIEW} for the user's approval. Every change is
 * broadcast as {@code analysis.updated} (SSE in M4).
 */
@RestController
@RequestMapping("/api/analyses")
public class AnalysisController {

    private static final Logger log = LoggerFactory.getLogger(AnalysisController.class);

    static final int MAX_DESCRIPTION_CHARS = 20_000;

    private final AnalysisRegistry registry;
    private final AnalysisPipeline pipeline;
    private final CvContextStore cvStore;
    private final ModelContextService models;

    public AnalysisController(AnalysisRegistry registry, AnalysisPipeline pipeline,
            CvContextStore cvStore, ModelContextService models) {
        this.registry = registry;
        this.pipeline = pipeline;
        this.cvStore = cvStore;
        this.models = models;
    }

    /** Starts a new analysis: snapshots the active CV + model, creates QUEUED and returns immediately. */
    @PostMapping
    public ResponseEntity<AnalysisSummary> create(@RequestBody(required = false) CreateAnalysisRequest body) {
        String posting = body == null ? null : body.jobPosting();
        if (posting == null || posting.isBlank()) {
            throw new ApiException(400, "MISSING_JOB_DESCRIPTION", "Job posting missing",
                    "The 'jobPosting' field with the job posting text is required.");
        }
        if (posting.length() > MAX_DESCRIPTION_CHARS) {
            throw new ApiException(400, "JOB_DESCRIPTION_TOO_LONG", "Job posting too long",
                    "The job posting has " + posting.length() + " characters; the limit is 20000.");
        }
        CvContext cv = cvStore.current().orElseThrow(() -> new ApiException(422, "CV_MISSING", "CV missing",
                "No active CV. Upload the CV first (POST /api/cv)."));
        if (!models.isReady()) {
            throw new ApiException(422, "MODEL_NOT_READY", "Model not ready",
                    "No ready LLM model. Load one in LM Studio and select it (PUT /api/llm/active-model).");
        }

        String id = UUID.randomUUID().toString();
        String modelId = models.activeModelId();
        Analysis analysis = new Analysis(id, cv.fileName(), cv.extractedText(), posting, modelId);
        registry.create(analysis);
        // Never log CV/posting content (AGENTS.md): only the analysis id.
        log.info("Created analysis {} with model {}", id, modelId);
        AnalysisSummary created = analysis.summary(); // the QUEUED snapshot the 202 body must report
        pipeline.start(analysis); // async: QUEUED → EVALUATING → AWAITING_REVIEW

        return ResponseEntity.accepted().body(created);
    }

    /** All analyses, newest first (docs/API.md §4); the registry is in-memory, so empty after a restart. */
    @GetMapping
    public List<AnalysisSummary> list() {
        return registry.findAll().stream()
                .sorted(Comparator.comparing(Analysis::createdAt).reversed())
                .map(Analysis::summary)
                .toList();
    }

    @GetMapping("/{id}")
    public AnalysisDetail detail(@PathVariable String id) {
        Analysis analysis = require(id);
        Evaluation evaluation = analysis.evaluation();
        boolean resultAvailable = analysis.stage() == AnalysisStage.COMPLETED
                && analysis.resultFile() != null && Files.exists(analysis.resultFile());
        return new AnalysisDetail(id, analysis.title(), analysis.stage(), analysis.score(), analysis.progress(),
                analysis.attemptId(), analysis.interruptedStage(), analysis.stageMessage(),
                analysis.createdAt(), analysis.updatedAt(), analysis.modelId(), analysis.errorCode(),
                analysis.errorMessage(),
                analysis.sourceUrl(), analysis.postingText(), analysis.postingSource(), analysis.postingTruncated(),
                evaluation == null ? null : evaluation.summary(),
                evaluation == null ? List.of() : evaluation.matchedKeywords(),
                evaluation == null ? List.of() : evaluation.missingKeywords(),
                analysis.suggestions(),
                analysis.appliedChanges(),
                resultAvailable ? "/api/analyses/" + id + "/result" : null);
    }

    /** Renames the analysis; the user title is never auto-overwritten (§6.2). */
    @PatchMapping("/{id}")
    public AnalysisSummary rename(@PathVariable String id, @RequestBody(required = false) RenameAnalysisRequest body) {
        if (body == null || body.title() == null || body.title().isBlank()) {
            throw new ApiException(400, "TITLE_BLANK", "Title blank",
                    "Provide a non-blank title for the analysis.");
        }
        Analysis analysis = require(id);
        analysis.rename(body.title());
        return analysis.summary();
    }

    /** Approves suggestions and starts stage 2 (docs/API.md §4); unknown ids are ignored. */
    @PostMapping("/{id}/apply")
    public ResponseEntity<AnalysisSummary> apply(@PathVariable String id,
            @RequestBody(required = false) ApplyAnalysisRequest body) {
        Analysis analysis = require(id);
        if (analysis.stage() != AnalysisStage.AWAITING_REVIEW) {
            throw new ApiException(409, "NOT_AWAITING_REVIEW", "Not awaiting review",
                    "Suggestions can only be approved while the analysis is AWAITING_REVIEW.");
        }
        List<String> requested = body == null ? List.of() : body.approvedSuggestionIds();
        if (requested == null || requested.isEmpty()) {
            throw new ApiException(422, "NO_SUGGESTIONS_APPROVED", "No suggestions approved",
                    "Approve at least one suggestion so the CV is not regenerated unchanged.");
        }
        List<String> known = requested.stream().filter(analysis::hasSuggestion).distinct().toList();
        if (known.isEmpty()) {
            throw new ApiException(422, "NO_SUGGESTIONS_APPROVED", "No suggestions approved",
                    "None of the approved suggestion ids match this analysis.");
        }
        analysis.beginRewrite(known); // synchronous: the 202 body already shows REWRITING
        AnalysisSummary rewriting = analysis.summary();
        pipeline.apply(analysis);
        return ResponseEntity.accepted().body(rewriting);
    }

    /** Re-runs the failed stage (docs/API.md §4). */
    @PostMapping("/{id}/retry")
    public ResponseEntity<AnalysisSummary> retry(@PathVariable String id) {
        Analysis analysis = require(id);
        if (analysis.stage() != AnalysisStage.FAILED) {
            throw new ApiException(409, "NOT_FAILED", "Not failed",
                    "Only a FAILED analysis can be retried.");
        }
        analysis.resetForRetry(); // the 202 body already shows the stage that will re-run
        AnalysisSummary retrying = analysis.summary();
        pipeline.retry(analysis);
        return ResponseEntity.accepted().body(retrying);
    }

    /** Downloads the improved PDF once the analysis is COMPLETED (docs/API.md §4). */
    @GetMapping("/{id}/result")
    public ResponseEntity<Resource> result(@PathVariable String id) {
        Analysis analysis = require(id);
        if (analysis.stage() != AnalysisStage.COMPLETED) {
            throw new ApiException(409, "NOT_READY", "Analysis not completed",
                    "The analysis is still running; wait for the COMPLETED stage.");
        }
        Path file = analysis.resultFile();
        if (file == null || !Files.exists(file)) {
            throw new ApiException(404, "RESULT_NOT_FOUND", "Result file missing",
                    "The result file is not available (it may have been cleaned up).");
        }
        String fileName = URLEncoder.encode("cv-improved-" + id + ".pdf", StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename*=UTF-8''" + fileName)
                .contentType(MediaType.APPLICATION_PDF)
                .body(new FileSystemResource(file));
    }

    /** Removes the analysis and its files (docs/API.md §4); other analyses are unaffected. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        Analysis analysis = require(id);
        registry.remove(id); // broadcasts analysis.deleted
        Path file = analysis.resultFile();
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.warn("Could not delete result file for analysis {}: {}", id, e.getMessage());
            }
        }
        return ResponseEntity.noContent().build();
    }

    private Analysis require(String id) {
        return registry.find(id).orElseThrow(() -> new ApiException(404, "ANALYSIS_NOT_FOUND",
                "Analysis not found", "No analysis with this id (the registry is in-memory; a restart clears it)."));
    }
}