package pl.example.cvchecker.sse;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import pl.example.cvchecker.job.CvJob;
import pl.example.cvchecker.job.JobEvent;
import pl.example.cvchecker.job.Stage;

/**
 * SSE layer (plan §6.6): one emitter per client connection, replay from the job's event buffer,
 * heartbeat keepalives, and cleanup on disconnect or terminal state.
 *
 * <p>Replay and live dispatch are serialized with {@link CvJob#eventLock} — the same lock that guards
 * appends — so a subscriber either sees an appended event in its replay snapshot or receives it live:
 * never both, never neither (plan §6.6 "replay and appending happen under one lock per job").
 */
@Component
public class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);

    /** One client connection to one job. */
    private record Subscription(CvJob job, SseEmitter emitter) {}

    private final Map<String, List<Subscription>> subscriptions = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final ScheduledExecutorService heartbeatScheduler;

    public SseHub(ObjectMapper mapper,
                  @Value("${cvchecker.sse.heartbeat-interval:15s}") Duration heartbeatInterval) {
        this.mapper = mapper;
        var scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        long millis = Math.max(1L, heartbeatInterval.toMillis());
        this.heartbeatScheduler = scheduler;
        scheduler.scheduleAtFixedRate(this::sendHeartbeats, millis, millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Registers a client connection and sends the replay (events after {@code lastEventId}, or all
     * events when 0) before returning. If the job is already terminal, the emitter is completed
     * immediately after the replay (plan §6.6).
     */
    public SseEmitter subscribe(CvJob job, long lastEventId) {
        var emitter = new SseEmitter(0L); // no timeout: jobs take minutes; closed explicitly on terminal state
        Subscription sub = new Subscription(job, emitter);
        emitter.onCompletion(() -> unsubscribe(job.id(), sub));
        emitter.onError(error -> unsubscribe(job.id(), sub));
        emitter.onTimeout(emitter::complete);
        synchronized (job.eventLock) {
            List<JobEvent> replay = job.eventsAfter(lastEventId);
            subscriptions.computeIfAbsent(job.id(), key -> new CopyOnWriteArrayList<>()).add(sub);
            for (JobEvent event : replay) {
                if (!sendTo(sub, toSse(job, event))) {
                    unsubscribe(job.id(), sub);
                    break; // client already gone: stop the replay
                }
            }
        }
        if (job.isTerminal()) {
            emitter.complete();
            unsubscribe(job.id(), sub);
        }
        return emitter;
    }

    /**
     * Sends {@code event} to all subscribers of the job. The caller must hold {@link CvJob#eventLock}
     * (reentrant from the pipeline's append section), which keeps live dispatch ordered after replay
     * registration. A broken emitter is removed and does not block the others (plan §6.6).
     */
    public void dispatch(CvJob job, JobEvent event) {
        List<Subscription> subs = subscriptions.get(job.id());
        if (subs == null || subs.isEmpty()) return;
        SseEmitter.SseEventBuilder sse = toSse(job, event);
        for (Subscription sub : subs) {
            if (!sendTo(sub, sse)) unsubscribe(job.id(), sub);
        }
        if (job.isTerminal()) closeAll(job);
    }

    /** Sends a {@code :keepalive} comment to every non-terminal job's subscribers (~15 s cadence). */
    private void sendHeartbeats() {
        for (List<Subscription> subs : subscriptions.values()) {
            if (subs.isEmpty()) continue;
            CvJob job = subs.get(0).job();
            if (job.isTerminal()) continue;
            synchronized (job.eventLock) { // serialize with dispatch/replay sends on the same emitters
                for (Subscription sub : subs) {
                    try {
                        sub.emitter().send(SseEmitter.event().comment("keepalive"));
                    } catch (IOException | IllegalStateException e) {
                        unsubscribe(job.id(), sub);
                    }
                }
            }
        }
    }

    /** Completes every emitter of a terminal job and drops its subscription list. */
    private void closeAll(CvJob job) {
        List<Subscription> subs = subscriptions.remove(job.id());
        if (subs == null) return;
        for (Subscription sub : subs) {
            try {
                sub.emitter().complete();
            } catch (IllegalStateException ignored) { // already completed or disconnected
            }
        }
    }

    private void unsubscribe(String jobId, Subscription sub) {
        List<Subscription> subs = subscriptions.get(jobId);
        if (subs != null) subs.remove(sub);
    }

    /** Serializes and sends one SSE frame; returns false when the client is gone. */
    private boolean sendTo(Subscription sub, SseEmitter.SseEventBuilder sse) {
        try {
            sub.emitter().send(sse);
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE client for job {} disconnected", sub.job().id());
            return false;
        }
    }

    /** Builds the SSE frame (id, event name, JSON data per docs/API.md) for one stored event. */
    private SseEmitter.SseEventBuilder toSse(CvJob job, JobEvent event) {
        String name = switch (event.stage()) {
            case COMPLETED -> "completed";
            case FAILED -> "failed";
            default -> "status";
        };
        Object data = switch (event.stage()) {
            case COMPLETED -> new CompletedData(job.id(), Stage.COMPLETED, 100,
                    "/api/cv-jobs/" + job.id() + "/result", reportOf(job));
            case FAILED -> new FailedData(job.id(), Stage.FAILED, job.errorCode(), event.message());
            default -> new StatusData(job.id(), event.stage(), event.message(), event.progress());
        };
        try {
            return SseEmitter.event()
                    .id(Long.toString(event.id()))
                    .name(name)
                    .data(mapper.writeValueAsBytes(data));
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize SSE payload for event " + event.id(), e);
        }
    }

    /** Report attached to the completed event; populated by the real pipeline (plan §6.7). */
    private Object reportOf(CvJob job) {
        return job.report();
    }
}
