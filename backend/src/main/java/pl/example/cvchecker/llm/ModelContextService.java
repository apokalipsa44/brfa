package pl.example.cvchecker.llm;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import pl.example.cvchecker.api.ApiException;
import pl.example.cvchecker.sse.ModelsUpdatedEvent;

/**
 * The persistent model context (docs/API.md §3): the active model id the analyses snapshot, plus
 * readiness computed from LM Studio's loaded state and a one-token probe. "Ready" means the
 * backend can actually use the model — active id set, loaded instance, and the model answers.
 */
@Service
public class ModelContextService {

    private final LmStudioClient client;
    private final ApplicationEventPublisher events;
    private final String nativeBaseUrl;

    private volatile String activeModelId;

    public ModelContextService(LmStudioClient client, ApplicationEventPublisher events,
            @Value("${cvchecker.lmstudio.base-url}") String nativeBaseUrl) {
        this.client = client;
        this.events = events;
        this.nativeBaseUrl = nativeBaseUrl;
    }

    /** The active model id, or {@code null} when none was ever selected. */
    public String activeModelId() {
        return activeModelId;
    }

    /** Whether a new analysis may start right now (docs/API.md §3). */
    public boolean isReady() {
        return state().ready();
    }

    /** Current model state: candidates, active selection, and whether it is usable right now. */
    public ModelState state() {
        LmStudioModelList list = client.listModels();
        if (!list.reachable()) {
            return new ModelState(List.of(), activeModelId, false,
                    "LM Studio unreachable at " + nativeBaseUrl + ". Is the server running?");
        }
        String active = activeModelId;
        if (active == null || active.isBlank()) {
            return new ModelState(list.models(), null, false, "No model selected");
        }
        boolean loaded = list.models().stream().anyMatch(m -> m.id().equals(active) && m.loaded());
        if (!loaded) {
            return new ModelState(list.models(), active, false, "The selected model is not loaded");
        }
        if (!client.probe(active)) {
            return new ModelState(list.models(), active, false, "LM Studio did not answer");
        }
        return new ModelState(list.models(), active, true, null);
    }

    /**
     * Selects the active model, ensuring it is loaded (best-effort unload-then-load), probes it and
     * broadcasts {@code models.updated}. A selection is recorded even when LM Studio is down or the
     * load fails — the returned state reports {@code ready:false} with a reason instead of crashing.
     *
     * @throws ApiException {@code MODEL_BLANK} when the id is blank, {@code MODEL_NOT_FOUND} when the
     *     id is not among the models LM Studio knows (and it answered)
     */
    public ModelState select(String modelId) {
        String model = modelId == null ? "" : modelId.trim();
        if (model.isBlank()) {
            throw new ApiException(400, "MODEL_BLANK", "Model missing",
                    "Provide the id of the model to select.");
        }
        LmStudioModelList list = client.listModels();
        if (list.reachable() && list.models().stream().noneMatch(m -> m.id().equals(model))) {
            throw new ApiException(400, "MODEL_NOT_FOUND", "Model not found",
                    "LM Studio does not know the model \"" + model + "\".");
        }
        this.activeModelId = model;
        // Best effort: a failure (unreachable/VRAM) is reported via state, not thrown (docs/API.md §3).
        if (list.reachable()) {
            client.unloadAllAndLoad(model);
        }
        events.publishEvent(new ModelsUpdatedEvent(this.activeModelId));
        return state();
    }
}