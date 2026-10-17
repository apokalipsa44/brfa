package pl.example.cvchecker.llm;

/**
 * One posting requirement and how the CV covers it (prompts/analyze.st Steps 1-2). {@code cvEvidence}
 * is a verbatim CV quote for MET/PARTIAL requirements and an empty string for MISSING ones. Records
 * are immutable: the analysis sanitizer rebuilds them when it corrects a status.
 */
public record Requirement(String text, RequirementType type, RequirementStatus status, String cvEvidence) {
}
