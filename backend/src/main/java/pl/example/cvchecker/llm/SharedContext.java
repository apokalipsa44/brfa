package pl.example.cvchecker.llm;

/**
 * Builds the CV + posting context block shared by the two LLM steps (evaluation and rewrite) so a
 * local backend can reuse the KV cache of that prefix across calls (docs/DECISIONS.md). The block
 * is placed first and is byte-identical for both calls; each step appends its own instructions
 * after it, leaving the common tokens as a stable prefix. CV/posting text is untrusted: it stays
 * inside the {@code <cv>}/{@code <job>} delimiters and is never logged (AGENTS.md).
 */
final class SharedContext {

    /** Long inputs blow the local model's context window, so the CV side is truncated (plan §6.3.4). */
    static final int MAX_CV_CHARS = 15_000;

    private SharedContext() {
    }

    /** The shared, delimited prefix for one analysis: {@code <cv>…</cv><job>…</job>} + a trailing newline. */
    static String of(String cvText, String jobDescription) {
        return cvOnly(cvText) + "<job>\n" + jobDescription + "\n</job>\n";
    }

    /**
     * The CV-only prefix for the standalone CV builder (docs/API.md §10), which has no posting:
     * {@code <cv>…</cv>} + a trailing newline, byte-identical to the CV part of {@link #of(String, String)}.
     */
    static String cvOnly(String cvText) {
        return "<cv>\n" + truncate(cvText) + "\n</cv>\n";
    }

    private static String truncate(String text) {
        if (text != null && text.length() > MAX_CV_CHARS) {
            return text.substring(0, MAX_CV_CHARS) + "\n[truncated: CV text exceeded the model input limit]";
        }
        return text;
    }
}
