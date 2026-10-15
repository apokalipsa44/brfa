package pl.example.cvchecker.posting;

import pl.example.cvchecker.analysis.PostingSource;

/**
 * The posting text resolved from a link (docs/API.md §4.1): the cleaned text, where it came from, and
 * the employer/position used for the auto-title before evaluation (which may later refine it).
 */
public record ResolvedPosting(String text, PostingSource source, boolean truncated, String employer,
        String position) {
}
