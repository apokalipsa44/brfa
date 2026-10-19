package pl.example.cvchecker.cv;

/**
 * Lifecycle of the standalone CV build (docs/API.md §10). {@code NONE} means no build has run yet;
 * {@code COMPLETED} and {@code FAILED} are terminal, and a new {@code POST} starts a fresh build.
 */
public enum CvImprovementStage {
    NONE,
    REWRITING,
    RENDERING,
    COMPLETED,
    FAILED
}
