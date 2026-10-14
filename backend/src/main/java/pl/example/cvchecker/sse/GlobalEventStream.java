package pl.example.cvchecker.sse;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import pl.example.cvchecker.analysis.AnalysisDeletedEvent;
import pl.example.cvchecker.analysis.AnalysisUpdatedEvent;

/**
 * The single, app-global SSE stream behind {@code GET /api/events} (docs/API.md §7). One emitter per
 * open tab; removing the per-job connections frees the browser's per-domain connection budget.
 *
 * <p>Events carry a global, monotonically increasing numeric {@code id}. A bounded replay buffer
 * ({@code cvchecker.events.replay-buffer}, default 1000) lets a reconnecting client catch up: it sends
 * {@code Last-Event-ID} and receives every buffered event after it, then continues live. A connect
 * <em>without</em> {@code Last-Event-ID} gets live events only, because the client already loaded full
 * state over REST.
 *
 * <p>Id assignment, buffering and fan-out all run under one lock, and subscriber registration runs
 * under the same lock, so a subscriber can never miss or duplicate an event: it either appears in the
 * replay tail or is delivered live, never both. The stream never closes for terminal states — the
 * client learns about removals from {@code analysis.deleted}.
 */
@Component
public class GlobalEventStream {

    private static final Logger log = LoggerFactory.getLogger(GlobalEventStream.class);

    /** One buffered/streamed frame: assigned id, SSE event name, pre-serialized JSON data. */
    private record Frame(long id, String name, String json) {}

    /** Serializes id assignment, buffer mutation and every send, so replay and live dispatch never race. */
    private final Object lock = new Object();

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ArrayDeque<Frame> replay = new ArrayDeque<>();
    private final int replayBufferSize;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService heartbeatScheduler;

    private long sequence;

    public GlobalEventStream(ObjectMapper mapper,
            @Value("${cvchecker.events.replay-buffer:1000}") int replayBufferSize,
            @Value("${cvchecker.sse.heartbeat-interval:15s}") Duration heartbeatInterval) {
        this.mapper = mapper;
        this.replayBufferSize = Math.max(1, replayBufferSize);
        var scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "events-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        this.heartbeatScheduler = scheduler;
        long millis = Math.max(1L, heartbeatInterval.toMillis());
        scheduler.scheduleAtFixedRate(this::sendHeartbeats, millis, millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Registers one client connection. {@code lastEventId} is the value of the {@code Last-Event-ID}
     * header, or {@code null} for a fresh connection (live events only). If given, every buffered
     * event with a greater id is replayed before returning. The stream is never completed here.
     */
    public SseEmitter subscribe(Long lastEventId) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout: the workspace lives as long as the tab
        synchronized (lock) {
            emitters.add(emitter);
            if (lastEventId != null) {
                for (Frame frame : replay) {
                    if (frame.id() > lastEventId && !send(emitter, frame)) {
                        emitters.remove(emitter);
                        return emitter; // client already gone: stop the replay
                    }
                }
            }
        }
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
        });
        return emitter;
    }

    @EventListener
    public void onAnalysisUpdated(AnalysisUpdatedEvent event) {
        publish("analysis.updated", event.summary());
    }

    @EventListener
    public void onAnalysisDeleted(AnalysisDeletedEvent event) {
        publish("analysis.deleted", Map.of("analysisId", event.analysisId()));
    }

    @EventListener
    public void onModelsUpdated(ModelsUpdatedEvent event) {
        publish("models.updated", Map.of());
    }

    @EventListener
    public void onCvUpdated(CvUpdatedEvent event) {
        publish("cv.updated", Map.of());
    }

    /** Assigns the next global id, buffers the frame, and fans it out to every open connection. */
    void publish(String name, Object data) {
        String json = serialize(name, data);
        synchronized (lock) {
            Frame frame = new Frame(++sequence, name, json);
            replay.addLast(frame);
            while (replay.size() > replayBufferSize) {
                replay.removeFirst();
            }
            dispatch(frame);
        }
    }

    /** Sends one pre-built frame to all subscribers, dropping the ones that disconnected. */
    private void dispatch(Frame frame) {
        if (emitters.isEmpty()) return;
        for (SseEmitter emitter : new ArrayList<>(emitters)) {
            if (!send(emitter, frame)) {
                emitters.remove(emitter);
            }
        }
    }

    /** Heartbeat: a named {@code ping} event (not buffered, not replayed) to keep proxies awake. */
    private void sendHeartbeats() {
        synchronized (lock) {
            if (emitters.isEmpty()) return;
            Frame ping = new Frame(++sequence, "ping", "{}");
            dispatch(ping);
        }
    }

    /** Writes one SSE frame; returns false when the client has gone away. */
    private boolean send(SseEmitter emitter, Frame frame) {
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(frame.id()))
                    .name(frame.name())
                    .data(frame.json(), MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("Global SSE client disconnected");
            return false;
        }
    }

    private String serialize(String name, Object data) {
        try {
            return mapper.writeValueAsString(data);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize SSE payload for event " + name, e);
        }
    }
}