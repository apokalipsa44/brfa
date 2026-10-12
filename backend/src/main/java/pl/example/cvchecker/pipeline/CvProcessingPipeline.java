package pl.example.cvchecker.pipeline;

import pl.example.cvchecker.job.CvJob;

/**
 * Processing pipeline contract (plan §6.7). The controller only starts jobs; stages run
 * asynchronously and report progress through {@code SseHub}.
 */
public interface CvProcessingPipeline {

    /** Emits QUEUED and runs the stages on the dedicated pipeline executor. */
    void start(CvJob job);
}
