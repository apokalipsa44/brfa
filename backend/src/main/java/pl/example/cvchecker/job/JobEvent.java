package pl.example.cvchecker.job;

import java.time.Instant;

/** One event in a job's stream. {@code id} increases within the job and doubles as the SSE Last-Event-ID (plan §6.6). */
public record JobEvent(long id, String jobId, Stage stage, String message, Integer progress, Instant at, Object payload) {
}
