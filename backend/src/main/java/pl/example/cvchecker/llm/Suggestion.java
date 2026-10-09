package pl.example.cvchecker.llm;

public record Suggestion(String section, String problem, String recommendation, Priority priority) {
}
