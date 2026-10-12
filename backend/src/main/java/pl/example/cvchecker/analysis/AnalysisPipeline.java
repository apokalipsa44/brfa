package pl.example.cvchecker.analysis;

/**
 * Two-stage processing pipeline (docs/API.md §1.1). The controller drives the transitions around
 * {@code AWAITING_REVIEW}: {@code start} runs stage 1, {@code apply} runs stage 2 (the controller
 * already moved the analysis to REWRITING), {@code retry} re-runs the stage that failed. All three
 * run asynchronously and report every change via {@link AnalysisUpdatedEvent}.
 */
public interface AnalysisPipeline {

    /** Stage 1: QUEUED → EVALUATING → AWAITING_REVIEW (or FAILED). */
    void start(Analysis analysis);

    /** Stage 2 (called when already REWRITING): rewrite → GENERATING_PDF → COMPLETED (or FAILED). */
    void apply(Analysis analysis);

    /** Re-runs the failed stage after {@link Analysis#resetForRetry()}. */
    void retry(Analysis analysis);
}