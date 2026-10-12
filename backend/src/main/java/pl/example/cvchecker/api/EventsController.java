package pl.example.cvchecker.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import pl.example.cvchecker.sse.GlobalEventStream;

/**
 * The single app-wide SSE stream (docs/API.md §7). One connection per open tab receives every
 * {@code analysis.updated} / {@code analysis.deleted} / {@code models.updated} / {@code ping} event.
 */
@RestController
public class EventsController {

    private final GlobalEventStream stream;

    public EventsController(GlobalEventStream stream) {
        this.stream = stream;
    }

    /**
     * Opens the stream. Without a {@code Last-Event-ID} header the client gets live events only (it
     * already loaded full state over REST); with one, the buffered events after it are replayed first.
     */
    @GetMapping(value = "/api/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        return stream.subscribe(parseLastEventId(lastEventId));
    }

    /** {@code null} means "no header": live only. A parsed value replays buffered events with id > n. */
    private static Long parseLastEventId(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return null; // malformed id: treat as a fresh connection rather than replaying everything
        }
    }
}