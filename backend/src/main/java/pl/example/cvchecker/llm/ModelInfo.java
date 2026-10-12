package pl.example.cvchecker.llm;

/** One LLM model LM Studio knows, with whether it is loaded in memory right now (docs/API.md §3). */
public record ModelInfo(String id, boolean loaded) {
}