package pl.example.cvchecker.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import pl.example.cvchecker.analysis.AnalysisRegistry;
import pl.example.cvchecker.llm.ModelContextService;
import pl.example.cvchecker.llm.ModelState;

/**
 * LLM model context (docs/API.md §3). {@code GET /api/llm/models} reports the full state (candidates,
 * active selection, readiness); {@code PUT /api/llm/active-model} switches it.
 */
@RestController
public class ModelController {

    private final ModelContextService models;
    private final AnalysisRegistry analyses;

    public ModelController(ModelContextService models, AnalysisRegistry analyses) {
        this.models = models;
        this.analyses = analyses;
    }

    /** Model state: candidates, active selection and whether it is ready (docs/API.md §3). */
    @GetMapping("/api/llm/models")
    public ModelState modelState() {
        return models.state();
    }

    /** Selects the active model, keeps it loaded and reports the resulting state (docs/API.md §3). */
    @PutMapping("/api/llm/active-model")
    public ModelState setActiveModel(@RequestBody(required = false) ActiveModelRequest request) {
        String modelId = requireModelId(request == null ? null : request.modelId());
        requireNoRunningAnalysis();
        return models.select(modelId);
    }

    /**
     * A switch unloads the currently loaded model (docs/API.md §6.3), which would abort any analysis
     * using it — so it is refused while a run is active; the client retries once it pauses or ends.
     */
    private void requireNoRunningAnalysis() {
        boolean running = analyses.findAll().stream().anyMatch(a -> a.stage().isCancellable());
        if (running) {
            throw new ApiException(409, "MODEL_SWITCH_BUSY", "Model switch not allowed",
                    "An analysis is running. Wait for it to finish or cancel it before switching the model.");
        }
    }

    /** The contract rejects a missing/blank model id before any LM Studio call (docs/API.md §3). */
    private static String requireModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new ApiException(400, "MODEL_BLANK", "Model missing",
                    "Provide the id of the model to select.");
        }
        return modelId.trim();
    }

    /** Request body of {@code PUT /api/llm/active-model}: the model id (docs/API.md §3). */
    private record ActiveModelRequest(String modelId) {
    }
}
