package pl.example.cvchecker.analysis;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A cancelled run frees the single pipeline slot so the next queued analysis starts (docs/API.md
 * §1.1). The pipeline executor defaults to one thread, so this is the property that keeps a stuck
 * analysis from blocking the whole workspace.
 */
class AnalysisRunsTest {

    private ThreadPoolTaskExecutor executor;
    private AnalysisRuns runs;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("test-pipeline-");
        executor.initialize();
        runs = new AnalysisRuns(executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void abortFreesTheSlotForTheNextQueuedRun() throws Exception {
        Analysis first = new Analysis("a1", "cv.pdf", "cv", "job", "qwen");
        Analysis second = new Analysis("a2", "cv.pdf", "cv", "job", "qwen");

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondRan = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        runs.submit(first, () -> {
            firstStarted.countDown();
            try {
                release.await(); // blocks until interrupted by cancel()
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();

        runs.submit(second, secondRan::countDown);
        assertThat(secondRan.await(200, TimeUnit.MILLISECONDS))
                .as("the second run must wait while the only slot is busy").isFalse();

        runs.abort("a1");

        assertThat(secondRan.await(2, TimeUnit.SECONDS))
                .as("cancelling the first run must free the executor slot").isTrue();
        assertThat(runs.isRunning("a1")).isFalse();
    }

    @Test
    void abortCancelsTheRunsRegisteredHandle() throws Exception {
        Analysis analysis = new Analysis("a1", "cv.pdf", "cv", "job", "qwen");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        RunHandle handle = runs.submit(analysis, () -> {
            started.countDown();
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
        });
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        runs.abort("a1");

        assertThat(handle.isCancelled()).isTrue();
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).as("the worker thread is interrupted").isTrue();
    }
}
