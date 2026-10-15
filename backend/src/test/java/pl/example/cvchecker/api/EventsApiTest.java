package pl.example.cvchecker.api;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import pl.example.cvchecker.llm.LmStudioClient;
import pl.example.cvchecker.llm.LmStudioModelList;
import pl.example.cvchecker.llm.ModelInfo;
import pl.example.cvchecker.pdf.TestPdfs;
import pl.example.cvchecker.storage.CvContextStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * End-to-end tests for the single global SSE stream {@code GET /api/events} (docs/API.md §7, M4).
 *
 * <p>The stream never completes (terminal analyses do not close it), so the tests read the async
 * {@code MockHttpServletResponse} incrementally while events are produced, instead of dispatching to
 * completion like the per-job SSE tests.
 */
@SpringBootTest(properties = {
        "cvchecker.pipeline.mode=simulated",
        "cvchecker.pipeline.stage-delay=100ms",
        "cvchecker.sse.heartbeat-interval=50ms",
        "cvchecker.persistence=memory"
})
@AutoConfigureMockMvc
class EventsApiTest {

    private static final String MODEL = "qwen/iq3_s.gguf";

    @Autowired
    MockMvc mvc;

    @Autowired
    CvContextStore cvStore;

    @MockitoBean
    LmStudioClient lmClient;

    @Test
    void liveStreamCarriesTheWholeAnalysisLifecycle() throws Exception {
        ready();
        MvcResult stream = openStream(null);

        String id = createAnalysis(); // also emits models.updated (active model selection)
        awaitBody(stream, body -> body.contains("\"id\":\"" + id + "\"")
                && body.contains("\"stage\":\"AWAITING_REVIEW\""));
        applySuggestion(id, "s1");
        awaitBody(stream, body -> body.contains("\"id\":\"" + id + "\"")
                && body.contains("\"stage\":\"COMPLETED\""));

        List<Frame> frames = frames(stream);
        assertThat(frames.stream().map(Frame::id)).isSorted().doesNotHaveDuplicates();
        assertThat(frames).anySatisfy(f -> assertThat(f.name()).isEqualTo("models.updated"));

        List<String> stages = frames.stream()
                .filter(f -> "analysis.updated".equals(f.name()))
                .filter(f -> id.equals(f.str("$.id")))
                .map(f -> f.str("$.stage"))
                .toList();
        assertThat(stages).startsWith("QUEUED");
        assertThat(stages).contains("EVALUATING", "AWAITING_REVIEW", "REWRITING", "GENERATING_PDF");
        assertThat(stages).endsWith("COMPLETED");

        assertThat(frames).anySatisfy(f -> assertThat(f.str("$.title"))
                .isEqualTo("Acme · Senior Java Developer"));
    }

    @Test
    void reconnectWithLastEventIdReplaysOnlyLaterEvents() throws Exception {
        ready();
        String id = createAnalysis();

        MvcResult first = openStream(null);
        rename(id, "Replay marker A");
        rename(id, "Replay marker B");
        awaitBody(first, body -> body.contains("Replay marker B"));

        long afterA = frames(first).stream()
                .filter(f -> "Replay marker A".equals(f.str("$.title")))
                .findFirst().orElseThrow().id();

        MvcResult replay = openStream(Long.toString(afterA));
        awaitBody(replay, body -> body.contains("Replay marker B"));
        String replayed = body(replay);
        assertThat(replayed).contains("Replay marker B");
        assertThat(replayed).doesNotContain("Replay marker A");
    }

    @Test
    void deleteIsBroadcastAsAnalysisDeleted() throws Exception {
        ready();
        String id = createAnalysis();
        MvcResult stream = openStream(null);

        mvc.perform(delete("/api/analyses/" + id)).andExpect(status().isNoContent());
        awaitBody(stream, body -> body.contains("\"analysisId\":\"" + id + "\""));

        Frame deleted = frames(stream).stream()
                .filter(f -> "analysis.deleted".equals(f.name()))
                .findFirst().orElseThrow();
        assertThat(deleted.str("$.analysisId")).isEqualTo(id);
    }

    @Test
    void heartbeatPingArrives() throws Exception {
        MvcResult stream = openStream(null);
        awaitBody(stream, body -> frames(stream).stream().anyMatch(f -> "ping".equals(f.name())));
        Frame ping = frames(stream).stream().filter(f -> "ping".equals(f.name())).findFirst().orElseThrow();
        assertThat(ping.data().trim()).isEqualTo("{}");
    }

    @Test
    void uploadingCvIsBroadcastAsCvUpdated() throws Exception {
        MvcResult stream = openStream(null);

        mvc.perform(multipart("/api/cv")
                        .file(new MockMultipartFile("file", "cv.pdf", MediaType.APPLICATION_PDF_VALUE,
                                TestPdfs.pdf("Jan Kowalski", "Java, Spring Boot"))))
                .andExpect(status().isCreated());

        awaitBody(stream, body -> body.contains("cv.updated"));
        Frame updated = frames(stream).stream()
                .filter(f -> "cv.updated".equals(f.name()))
                .findFirst().orElseThrow();
        assertThat(updated.data().trim()).isEqualTo("{}");
    }

    private void ready() throws Exception {
        when(lmClient.listModels()).thenReturn(new LmStudioModelList(true, List.of(new ModelInfo(MODEL, true))));
        when(lmClient.probe(MODEL)).thenReturn(true);
        cvStore.place("cv.pdf", TestPdfs.pdf("Jan Kowalski", "Java, Spring Boot"), "Jan Kowalski, Java");
    }

    private String createAnalysis() throws Exception {
        MvcResult selected = mvc.perform(put("/api/llm/active-model").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":\"" + MODEL + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        if (!selected.getResponse().getContentAsString().contains("\"ready\":true")) {
            fail("model was not ready: " + selected.getResponse().getContentAsString());
        }

        MvcResult result = mvc.perform(post("/api/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobPosting\":\"Senior Java Developer wanted\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        String id = JsonPath.parse(result.getResponse().getContentAsString()).read("$.id");
        awaitAnalysisStage(id, "AWAITING_REVIEW");
        return id;
    }

    private void applySuggestion(String id, String suggestionId) throws Exception {
        mvc.perform(post("/api/analyses/" + id + "/apply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedSuggestionIds\":[\"" + suggestionId + "\"]}"))
                .andExpect(status().isAccepted());
    }

    private void rename(String id, String title) throws Exception {
        mvc.perform(patch("/api/analyses/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isOk());
    }

    private void awaitAnalysisStage(String id, String stage) throws Exception {
        for (int i = 0; i < 100; i++) {
            MvcResult result = mvc.perform(get("/api/analyses/" + id)).andReturn();
            if (result.getResponse().getContentAsString().contains("\"stage\":\"" + stage + "\"")) {
                return;
            }
            Thread.sleep(50);
        }
        fail("analysis " + id + " did not reach " + stage);
    }

    private MvcResult openStream(String lastEventId) throws Exception {
        var builder = get("/api/events").accept(MediaType.TEXT_EVENT_STREAM);
        if (lastEventId != null) {
            builder.header("Last-Event-ID", lastEventId);
        }
        return mvc.perform(builder).andExpect(request().asyncStarted()).andReturn();
    }

    private void awaitBody(MvcResult stream, Predicate<String> condition) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (condition.test(body(stream))) {
                return;
            }
            Thread.sleep(50);
        }
        fail("stream did not satisfy the condition in time; body was:\n" + body(stream));
    }

    private static String body(MvcResult stream) {
        try {
            return stream.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private List<Frame> frames(MvcResult stream) {
        return parseFrames(body(stream));
    }

    /** One complete SSE frame: {@code id}, {@code event} name and the JSON {@code data} payload. */
    private record Frame(long id, String name, String data) {
        String str(String path) {
            try {
                Object value = JsonPath.parse(data).read(path);
                return value == null ? null : value.toString();
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** Splits the accumulated SSE body into complete frames; a partial trailing frame is ignored. */
    private static List<Frame> parseFrames(String body) {
        var frames = new ArrayList<Frame>();
        String[] blocks = body.split("\n\n");
        for (int i = 0; i < blocks.length; i++) {
            String block = blocks[i];
            Long id = null;
            String name = null;
            String data = null;
            for (String rawLine : block.split("\n")) {
                String line = rawLine.stripTrailing().replace("\r", "");
                if (line.startsWith("id:")) {
                    try {
                        id = Long.parseLong(line.substring(3).trim());
                    } catch (NumberFormatException ignored) {
                        // partial id line: ignore, the frame will be retried on the next read
                    }
                } else if (line.startsWith("event:")) {
                    name = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data = line.substring(5).stripLeading();
                }
            }
            boolean complete = i < blocks.length - 1 || body.endsWith("\n\n");
            if (complete && id != null && name != null && data != null) {
                frames.add(new Frame(id, name, data));
            }
        }
        return frames;
    }
}