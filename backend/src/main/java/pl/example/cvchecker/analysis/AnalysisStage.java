package pl.example.cvchecker.analysis;

/**
 * Analysis stages (docs/API.md §1.1). QUEUED→EVALUATING→AWAITING_REVIEW is stage 1; the pipeline
 * pauses at AWAITING_REVIEW until {@code POST /api/analyses/{id}/apply}, then stage 2 runs
 * REWRITING→GENERATING_PDF→COMPLETED. Any non-terminal stage can fail; retry re-runs it.
 */
public enum AnalysisStage {
    QUEUED, EVALUATING, AWAITING_REVIEW, REWRITING, GENERATING_PDF, COMPLETED, FAILED
}