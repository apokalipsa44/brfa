package pl.example.cvchecker.analysis;

import java.util.Set;

/**
 * Analysis stages (docs/API.md §1.1). For a link posting the pipeline first runs FETCHING_POSTING;
 * pasted text goes straight to EVALUATING. QUEUED→EVALUATING→AWAITING_REVIEW is stage 1; the
 * pipeline pauses at AWAITING_REVIEW until {@code POST /api/analyses/{id}/apply}, then stage 2 runs
 * REWRITING→GENERATING_PDF→COMPLETED. Any non-terminal stage can fail (FAILED) or be cancelled
 * (CANCELLED); retry re-runs the interrupted/failed stage.
 */
public enum AnalysisStage {
    QUEUED, FETCHING_POSTING, EVALUATING, AWAITING_REVIEW, REWRITING, GENERATING_PDF, COMPLETED, FAILED, CANCELLED;

    /** Stages a running or queued analysis can be cancelled from (docs/API.md §1.1). */
    public static final Set<AnalysisStage> CANCELLABLE = Set.of(
            QUEUED, FETCHING_POSTING, EVALUATING, REWRITING, GENERATING_PDF);

    /** True once the analysis reached a terminal (or terminal-like) state and is cleaned up by TTL. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /** True when the stage represents work in progress on the pipeline executor. */
    public boolean isCancellable() {
        return CANCELLABLE.contains(this);
    }
}
