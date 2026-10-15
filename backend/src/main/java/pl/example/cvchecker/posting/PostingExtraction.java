package pl.example.cvchecker.posting;

/**
 * Output of the posting-cleanup LLM call (docs/API.md §4.1 step 3): whether the page is actually a
 * job posting, the employer/position for the title, and the cleaned posting text with navigation,
 * ads and boilerplate removed.
 */
public record PostingExtraction(boolean jobPosting, String employer, String position, String text) {
}
