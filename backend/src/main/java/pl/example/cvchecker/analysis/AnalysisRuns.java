package pl.example.cvchecker.analysis;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Tracks the single in-flight run per analysis (docs/API.md §1.1) and submits pipeline work to the
 * bounded {@code pipelineExecutor}. Owning {@link RunHandle}s here (instead of Spring's plain
 * {@code @Async}) is what makes cancellation possible: the controller can abort the task, its calls
 * and its resources at any moment.
 */
@Component
public class AnalysisRuns {

    private final ThreadPoolTaskExecutor executor;
    private final ConcurrentMap<String, RunHandle> active = new ConcurrentHashMap<>();

    public AnalysisRuns(@Qualifier("pipelineExecutor") ThreadPoolTaskExecutor executor) {
        this.executor = executor;
    }

    /** Submits {@code work} for the analysis and returns its handle (already registered for cancel). */
    public RunHandle submit(Analysis analysis, Runnable work) {
        RunHandle handle = new RunHandle(analysis.id(), analysis.attemptId());
        active.put(analysis.id(), handle);
        Future<?> future = executor.submit(() -> {
            RunHandle.setCurrent(handle);
            try {
                work.run();
            } finally {
                RunHandle.clearCurrent();
                active.remove(analysis.id(), handle);
            }
        });
        handle.setFuture(future);
        return handle;
    }

    /** Aborts the in-flight run for the analysis, if any (state change is done by {@link Analysis#cancel()}). */
    public void abort(String analysisId) {
        RunHandle handle = active.get(analysisId);
        if (handle != null) handle.cancel();
    }

    /** True when a not-yet-cancelled run is registered for the analysis. */
    public boolean isRunning(String analysisId) {
        RunHandle handle = active.get(analysisId);
        return handle != null && !handle.isCancelled();
    }
}
