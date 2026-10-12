package pl.example.cvchecker.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import pl.example.cvchecker.llm.ModelContextService;
import pl.example.cvchecker.llm.ModelInfo;
import pl.example.cvchecker.llm.ModelState;

/**
 * LLM model context (docs/API.md §3). {@code GET /api/llm/models} reports the full state (candidates,
 * active selection, readiness); {@code PUT /api/llm/active-model} switches it. The old
 * {@code POST /api/llm/models/load} and {@code GET /api/llm/models/loaded} stay for the old frontend
 * until the deprecated flow is removed with the final iteration.
 */
@RestController
public class ModelController {

    private final ModelContextService models;

    public ModelController(ModelContextService models) {
        this.models = models;
    }

    /** Model state: candidates, active selection and whether it is ready (docs/API.md §3). */
    @GetMapping("/api/llm/models")
    public ModelState modelState() {
        return models.state();
    }

    /** Selects the active model, keeps it loaded and reports the resulting state (docs/API.md §3). */
    @PutMapping("/api/llm/active-model")
    public ModelState setActiveModel(@RequestBody ActiveModelRequest request) {
        return models.select(requireModelId(request.modelId()));
    }

    /** Deprecated (old frontend, docs/API.md §7): switches the active model and answers 204. */
    @PostMapping("/api/llm/models/load")
    public ResponseEntity<Void> loadModel(@RequestBody LoadModelRequest request) {
        models.select(requireModelId(request.model()));
        return ResponseEntity.noContent().build();
    }

    /** The contract rejects a missing/blank model id before any LM Studio call (docs/API.md §3). */
    private static String requireModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new ApiException(400, "MODEL_BLANK", "Model missing",
                    "Provide the id of the model to select.");
        }
        return modelId.trim();
    }

    /** Deprecated (old frontend, docs/API.md §7): reachable + loaded model keys. */
    @GetMapping("/api/llm/models/loaded")
    public LoadedModelsResponse loadedModels() {
        ModelState state = models.state();
        boolean unreachable = state.reason() != null && state.reason().startsWith("LM Studio unreachable");
        List<String> loaded = state.models().stream()
                .filter(ModelInfo::loaded)
                .map(ModelInfo::id)
                .toList();
        return new LoadedModelsResponse(!unreachable, loaded);
    }

    /** Request body of {@code PUT /api/llm/active-model}: the model id (docs/API.md §3). */
    private record ActiveModelRequest(String modelId) {
    }

    /** Request body of deprecated {@code POST /api/llm/models/load}. */
    private record LoadModelRequest(String model) {
    }

    /** Deprecated response: reachable + model keys with loaded instances (docs/API.md §7). */
    private record LoadedModelsResponse(boolean reachable, List<String> loaded) {
    }
}