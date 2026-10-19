package pl.example.cvchecker.cv;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import pl.example.cvchecker.api.ApiException;
import pl.example.cvchecker.api.CvImprovementState;
import pl.example.cvchecker.llm.CvBuildService;
import pl.example.cvchecker.llm.CvRewriteResult;
import pl.example.cvchecker.llm.LlmAbortedException;
import pl.example.cvchecker.llm.LlmInvalidOutputException;
import pl.example.cvchecker.llm.ModelContextService;
import pl.example.cvchecker.pdf.CvPdfRenderer;
import pl.example.cvchecker.storage.CvContext;
import pl.example.cvchecker.storage.CvContextStore;
import pl.example.cvchecker.storage.TempFileStorage;

/**
 * The standalone CV builder (docs/API.md §10): rewrites the active CV with a user-selected skill list
 * and renders an ATS PDF, fully independent of the analysis flow. It keeps one current build at a
 * time; a new build supersedes the previous result and deletes its file. Every stage change is
 * broadcast as {@code cv.improvement.updated}.
 */
@Service
public class CvImprovementService {

    private static final Logger log = LoggerFactory.getLogger(CvImprovementService.class);

    /** Bounds for the selected skill list (docs/API.md §10). */
    static final int MAX_SKILLS = 30;
    static final int MAX_SKILL_CHARS = 120;

    private final CvContextStore cvStore;
    private final ModelContextService models;
    private final CvBuildService builder;
    private final CvPdfRenderer renderer;
    private final TempFileStorage storage;
    private final ApplicationEventPublisher events;
    private final ThreadPoolTaskExecutor executor;

    private CvImprovementStage stage = CvImprovementStage.NONE;
    private List<String> skills = List.of();
    private String modelId;
    private Instant startedAt;
    private Instant finishedAt;
    private String errorCode;
    private String errorMessage;
    private Path resultFile;
    private String runId;

    public CvImprovementService(CvContextStore cvStore, ModelContextService models, CvBuildService builder,
            CvPdfRenderer renderer, TempFileStorage storage, ApplicationEventPublisher events,
            @Qualifier("cvImprovementExecutor") ThreadPoolTaskExecutor executor) {
        this.cvStore = cvStore;
        this.models = models;
        this.builder = builder;
        this.renderer = renderer;
        this.storage = storage;
        this.events = events;
        this.executor = executor;
    }

    /**
     * Validates the request (active CV + ready model + a non-empty, bounded skill list), starts the
     * build asynchronously and returns the state (already {@code REWRITING}).
     *
     * @throws ApiException 409 {@code IMPROVEMENT_RUNNING}, 400 {@code IMPROVEMENT_SKILLS_EMPTY} /
     *     {@code IMPROVEMENT_SKILL_TOO_LONG} / {@code IMPROVEMENT_TOO_MANY_SKILLS}, 422 {@code CV_MISSING}
     *     / {@code MODEL_NOT_READY}
     */
    public synchronized CvImprovementState start(List<String> requestedSkills) {
        if (isRunning()) {
            throw new ApiException(409, "IMPROVEMENT_RUNNING", "Build in progress",
                    "A CV build is already running. Wait for it to finish.");
        }
        List<String> selected = normalize(requestedSkills);
        CvContext cv = cvStore.current().orElseThrow(() -> new ApiException(422, "CV_MISSING", "CV missing",
                "No active CV. Upload the CV first (POST /api/cv)."));
        if (!models.isReady()) {
            throw new ApiException(422, "MODEL_NOT_READY", "Model not ready",
                    "No ready LLM model. Load one in LM Studio and select it (PUT /api/llm/active-model).");
        }
        String model = models.activeModelId();
        String id = UUID.randomUUID().toString();

        Path previous = resultFile;
        this.runId = id;
        this.skills = selected;
        this.modelId = model;
        this.startedAt = Instant.now();
        this.finishedAt = null;
        this.errorCode = null;
        this.errorMessage = null;
        this.resultFile = null;
        this.stage = CvImprovementStage.REWRITING;
        deleteQuietly(previous);
        String cvText = cv.extractedText();
        publish();

        executor.submit(() -> run(cvText, selected, model, id));
        return state();
    }

    /** The current build state (docs/API.md §10); {@code NONE} until the first build. */
    public synchronized CvImprovementState state() {
        boolean ready = stage == CvImprovementStage.COMPLETED && resultFile != null && Files.exists(resultFile);
        String resultUrl = ready ? "/api/cv/improvement/result?v=" + runId : null;
        return new CvImprovementState(stage, skills, modelId, startedAt, finishedAt, errorCode, errorMessage,
                resultUrl);
    }

    /** The completed PDF, or a 409/404 through {@link ApiException} (docs/API.md §10). */
    public synchronized Path resultPath() {
        if (stage != CvImprovementStage.COMPLETED) {
            throw new ApiException(409, "NOT_READY", "Build not completed",
                    "The CV build is still running; wait for the COMPLETED stage.");
        }
        if (resultFile == null || !Files.exists(resultFile)) {
            throw new ApiException(404, "RESULT_NOT_FOUND", "Result file missing",
                    "The improved CV is not available (a newer build may have replaced it).");
        }
        return resultFile;
    }

    private void run(String cvText, List<String> selected, String model, String id) {
        try {
            CvRewriteResult result = builder.build(cvText, selected, model, null);
            changeStage(id, CvImprovementStage.RENDERING);
            Path target = storage.cvImprovementPath(id);
            renderer.render(result.document(), target);
            synchronized (this) {
                if (!id.equals(runId)) { // superseded by a newer build: drop the stale result
                    deleteQuietly(target);
                    return;
                }
                this.resultFile = target;
                this.stage = CvImprovementStage.COMPLETED;
                this.finishedAt = Instant.now();
            }
            publish();
        } catch (Exception e) {
            synchronized (this) {
                if (!id.equals(runId)) {
                    return;
                }
                this.stage = CvImprovementStage.FAILED;
                this.finishedAt = Instant.now();
                this.errorCode = errorCodeFor(e);
                this.errorMessage = errorMessageFor(e);
            }
            // Never log CV content (AGENTS.md): only the run id and error.
            log.warn("CV build {} failed: {}", id, e.toString());
            publish();
        }
    }

    private void changeStage(String id, CvImprovementStage next) {
        synchronized (this) {
            if (!id.equals(runId)) {
                return;
            }
            this.stage = next;
        }
        publish();
    }

    private synchronized boolean isRunning() {
        return stage == CvImprovementStage.REWRITING || stage == CvImprovementStage.RENDERING;
    }

    private void publish() {
        events.publishEvent(new CvImprovementUpdatedEvent(state()));
    }

    /** Trims, drops blanks and de-duplicates the selection, enforcing the size limits (docs/API.md §10). */
    private static List<String> normalize(List<String> requested) {
        List<String> selected = new ArrayList<>();
        if (requested != null) {
            for (String raw : requested) {
                if (raw == null) {
                    continue;
                }
                String skill = raw.strip();
                if (skill.isEmpty()) {
                    continue;
                }
                if (skill.length() > MAX_SKILL_CHARS) {
                    throw new ApiException(400, "IMPROVEMENT_SKILL_TOO_LONG", "Skill too long",
                            "A selected skill is longer than " + MAX_SKILL_CHARS + " characters.");
                }
                if (!selected.contains(skill)) {
                    selected.add(skill);
                }
            }
        }
        if (selected.isEmpty()) {
            throw new ApiException(400, "IMPROVEMENT_SKILLS_EMPTY", "No skills selected",
                    "Select at least one skill to add to the CV.");
        }
        if (selected.size() > MAX_SKILLS) {
            throw new ApiException(400, "IMPROVEMENT_TOO_MANY_SKILLS", "Too many skills",
                    "Select at most " + MAX_SKILLS + " skills.");
        }
        return selected;
    }

    private static String errorCodeFor(Exception e) {
        if (e instanceof LlmInvalidOutputException) {
            return "LLM_INVALID_OUTPUT";
        }
        if (e instanceof LlmAbortedException) {
            return "LLM_ABORTED";
        }
        return "INTERNAL_ERROR";
    }

    private static String errorMessageFor(Exception e) {
        if (e instanceof LlmInvalidOutputException) {
            return "The model returned an invalid response. Try again.";
        }
        if (e instanceof LlmAbortedException) {
            return "The build was interrupted. Try again.";
        }
        return "The CV could not be built. Try again.";
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete a superseded CV build file: {}", e.getMessage());
        }
    }
}
