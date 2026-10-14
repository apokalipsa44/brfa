package pl.example.cvchecker.analysis;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;

import okhttp3.Call;

/**
 * Handle for one running analysis attempt (docs/API.md §1.1). It owns everything {@code /cancel}
 * must abort: the queued/running task, the worker thread's interrupt flag, every in-flight OkHttp
 * call and any temporary resource the run created.
 *
 * <p>The worker thread publishes its handle through {@link #current()} so
 * {@link pl.example.cvchecker.llm.LlmCallInterceptor} can register each blocking LLM call; a thread
 * interrupt alone does not abort a blocking OkHttp call (see docs/DECISIONS.md), so
 * {@link #register(Call)} + {@link #cancel()} is what actually frees the worker.
 */
public final class RunHandle {

    private static final ThreadLocal<RunHandle> CURRENT = new ThreadLocal<>();

    private final String analysisId;
    private final long attemptId;
    private final List<Call> inFlight = new CopyOnWriteArrayList<>();
    private final List<Runnable> closeActions = new CopyOnWriteArrayList<>();
    private volatile Future<?> future;
    private volatile boolean cancelled;

    RunHandle(String analysisId, long attemptId) {
        this.analysisId = analysisId;
        this.attemptId = attemptId;
    }

    /** The handle of the currently executing run, or null outside a pipeline worker thread. */
    public static RunHandle current() {
        return CURRENT.get();
    }

    static void setCurrent(RunHandle handle) {
        CURRENT.set(handle);
    }

    static void clearCurrent() {
        CURRENT.remove();
    }

    void setFuture(Future<?> future) {
        this.future = future;
        if (cancelled) future.cancel(true);
    }

    public String analysisId() {
        return analysisId;
    }

    public long attemptId() {
        return attemptId;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** Registers a blocking LLM call so {@link #cancel()} aborts it; cancels it at once if already cancelled. */
    public void register(Call call) {
        inFlight.add(call);
        if (cancelled) call.cancel();
    }

    public void unregister(Call call) {
        inFlight.remove(call);
    }

    /** Registers a resource (temporary file, stream) to release on cancel. */
    public void onCancel(Runnable closeAction) {
        closeActions.add(closeAction);
        if (cancelled) closeAction.run();
    }

    /** Aborts the run: interrupt the worker, cancel in-flight calls, release resources. Idempotent. */
    void cancel() {
        cancelled = true;
        Future<?> task = this.future;
        if (task != null) task.cancel(true);
        for (Call call : inFlight) call.cancel();
        for (Runnable closeAction : closeActions) {
            try {
                closeAction.run();
            } catch (RuntimeException ignored) {
                // best-effort cleanup
            }
        }
    }
}
