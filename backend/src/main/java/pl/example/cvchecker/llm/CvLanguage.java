package pl.example.cvchecker.llm;

import java.util.Locale;

/**
 * Best-effort detection of the CV's language, used to fill the {@code {{CV_LANGUAGE}}} placeholder in
 * prompts/analyze.st so the model writes the human-readable fields in the CV's own language (not the
 * posting's). Only Polish is recognised explicitly; anything else is treated as English. The
 * heuristic (enough Polish-only letters or a distinctive Polish word) is deliberately simple and
 * never blocks a call.
 */
final class CvLanguage {

    /** Letters that only exist in the Polish alphabet; a few of them are a reliable signal. */
    private static final String POLISH_LETTERS = "ąćęłńóśźż";

    private static final int POLISH_LETTER_THRESHOLD = 3;

    /** Distinctive Polish words that survive a plain substring search across line breaks. */
    private static final String[] POLISH_WORDS = {
            "doświadczenie", "umiejętności", "wykształcenie", "stanowisko", "wymagania", "język",
            "oraz", "jestem", "wyższe", "praca",
    };

    private CvLanguage() {
    }

    /** The instruction substituted for {@code {{CV_LANGUAGE}}} in the analysis prompt. */
    static String instructionFor(String cvText) {
        return "Write them in " + nameOf(cvText) + ".";
    }

    /** The detected CV language name, either {@code "Polish"} or {@code "English"}. */
    static String nameOf(String cvText) {
        return isPolish(cvText) ? "Polish" : "English";
    }

    private static boolean isPolish(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        long letters = lower.chars().filter(ch -> POLISH_LETTERS.indexOf(ch) >= 0).count();
        if (letters >= POLISH_LETTER_THRESHOLD) {
            return true;
        }
        for (String word : POLISH_WORDS) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
