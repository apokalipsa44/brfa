package pl.example.cvchecker.llm;

/**
 * Thin facade over LM Studio's HTTP API (docs/API.md §3). Split from {@link ModelContextService}
 * so the service logic (state + selection) is unit-testable without a live server.
 */
public interface LmStudioClient {

    /** Lists the LLM models LM Studio knows, with their loaded instances. Never throws for unreachable servers. */
    LmStudioModelList listModels();

    /** Best-effort switch: unloads all loaded instances, then loads the given model. Never throws. */
    void unloadAllAndLoad(String modelId);

    /** One-token chat completion to check the loaded model actually answers (docs/API.md §3). */
    boolean probe(String modelId);
}