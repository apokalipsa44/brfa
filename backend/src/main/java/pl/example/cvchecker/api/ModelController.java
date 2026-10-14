package pl.example.cvchecker.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import pl.example.cvchecker.llm.ModelContextService;
import pl.example.cvchecker.llm.ModelState;

/**
 * LLM model context (docs/API.md §3). {@code GET /api/llm/models} reports the full state (candidates,
 * active selection, readiness); {@code PUT /api/llm/active-model} switches it.
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
    public ModelState setActiveModel(@RequestBody(required = false) ActiveModelRequest request) {
        return models.select(requireModelId(request == null ? null : request.modelId()));
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
