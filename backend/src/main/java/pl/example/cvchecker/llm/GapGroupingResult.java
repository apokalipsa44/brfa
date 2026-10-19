package pl.example.cvchecker.llm;

import java.util.List;

/**
 * Structured output of LLM call #1 for grouped skills (prompts/group-gaps.st): every input gap term
 * translated to English and folded into one group per canonical skill. {@code members} must echo the
 * original input strings verbatim so the service can map them back to gap rows.
 */
public record GapGroupingResult(List<Group> groups) {

    public record Group(String label, List<String> members) {
    }
}
