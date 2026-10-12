package pl.example.cvchecker.llm;

import java.util.List;

/**
 * Result of listing LM Studio's native {@code GET /api/v1/models}: whether the server answered at
 * all, and — when it did — the LLM models ({@code type == "llm"}) with their loaded instances.
 * The {@code reachable} flag lets the service report an unreachable server instead of guessing.
 */
public record LmStudioModelList(boolean reachable, List<ModelInfo> models) {
}