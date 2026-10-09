package pl.example.cvchecker.job;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CvJobTest {

    private CvJob newJob() {
        return new CvJob("job-1", "Senior Java developer wanted", Path.of("/tmp/job-1.pdf"));
    }

    @Test
    void appendStatusAssignsIncreasingIdsAndUpdatesStage() {
        CvJob job = newJob();

        JobEvent first = job.appendStatus(Stage.EXTRACTING_TEXT, "Extracting text from the PDF", 10);
        JobEvent second = job.appendStatus(Stage.ANALYZING, "Analyzing CV match against the job posting", 35);

        assertThat(first.id()).isEqualTo(1);
        assertThat(second.id()).isEqualTo(2);
        assertThat(job.stage()).isEqualTo(Stage.ANALYZING);
        synchronized (job.eventLock) {
            assertThat(job.eventsUnderLock()).hasSize(2);
        }
    }

    @Test
    void failSetsTerminalStateAndErrorFields() {
        CvJob job = newJob();

        JobEvent failed = job.fail("PDF_NO_TEXT", "No text found in the PDF (it may be a scan)");

        assertThat(job.stage()).isEqualTo(Stage.FAILED);
        assertThat(job.errorCode()).isEqualTo("PDF_NO_TEXT");
        assertThat(job.errorMessage()).contains("scan");
        assertThat(failed.stage()).isEqualTo(Stage.FAILED);
    }

    @Test
    void concurrentAppendsNeverLoseOrDuplicateEvents() throws InterruptedException {
        CvJob job = newJob();
        int threads = 8;
        int perThread = 250;
        var executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    job.appendStatus(Stage.ANALYZING, "tick", 50);
                }
            });
        }
        executor.shutdown();
        assertThat(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        synchronized (job.eventLock) {
            var events = job.eventsUnderLock();
            assertThat(events).hasSize(threads * perThread);
            for (int i = 1; i < events.size(); i++) {
                assertThat(events.get(i).id()).isEqualTo(events.get(i - 1).id() + 1);
            }
        }
    }
}
