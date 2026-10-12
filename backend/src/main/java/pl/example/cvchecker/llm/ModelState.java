package pl.example.cvchecker.llm;

import java.util.List;

/**
 * The model context the frontend renders and the analyses snapshot (docs/API.md §3): every LLM
 * model LM Studio knows, the persistent active selection, and whether the backend can actually use
 * it right now ({@code ready} with a human-readable {@code reason} when it cannot).
 */
public record ModelState(List<ModelInfo> models, String activeModelId, boolean ready, String reason) {
}