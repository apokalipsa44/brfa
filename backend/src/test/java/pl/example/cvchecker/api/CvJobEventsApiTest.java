package pl.example.cvchecker.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end SSE tests against the simulated pipeline (milestone 4 acceptance): a client sees
 * successive stages, replay after a delayed connection works, and heartbeats arrive.
 */
@SpringBootTest(properties = {
        "cvchecker.pipeline.stage-delay=200ms",
        "cvchecker.sse.heartbeat-interval=50ms"
})
@AutoConfigureMockMvc
class CvJobEventsApiTest {

    private static final byte[] PDF_BYTES = ("%PDF-1.4\n%fake-cv-for-testing\n").getBytes(StandardCharsets.UTF_8);

    @Autowired
    MockMvc mvc;

    @Test
    void clientSeesSuccessiveStagesAndHeartbeat() throws Exception {
        String jobId = createJob();

        MvcResult result = openStream(jobId, null);
        String body = awaitCompletion(result).getResponse().getContentAsString();

        List<Frame> frames = parseFrames(body);
        assertThat(frames)
                .extracting(Frame::name)
                .containsExactly("status", "status", "status", "status", "status", "completed");
        assertThat(frames)
                .extracting(Frame::id)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(body).contains(":keepalive"); // heartbeat fired while the job was non-terminal
    }

    @Test
    void lateConnectionReplaysOnlyAfterLastEventId() throws Exception {
        String jobId = createJob();

        // First connection consumes the stream to completion, so the job is terminal afterwards.
        awaitCompletion(openStream(jobId, null));

        // Late connection with Last-Event-ID: 3 must receive only events after id 3.
        MvcResult second = openStream(jobId, "3");
        String body = awaitCompletion(second).getResponse().getContentAsString();

        List<Frame> frames = parseFrames(body);
        assertThat(frames)
                .extracting(Frame::id)
                .containsExactly(4L, 5L, 6L);
    }

    @Test
    void midRunConnectionLosesNothingAndDuplicatesNothing() throws Exception {
        String jobId = createJob();
        Thread.sleep(450); // connect while the simulated pipeline is still running

        MvcResult result = openStream(jobId, null);
        String body = awaitCompletion(result).getResponse().getContentAsString();

        // Replay of buffered events plus live ones: exactly ids 1..6, each once, in order.
        assertThat(parseFrames(body))
                .extracting(Frame::id)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    }

    @Test
    void unknownJobReturns404() throws Exception {
        mvc.perform(get("/api/cv-jobs/does-not-exist/events").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
    }

    private String createJob() throws Exception {
        MvcResult result = mvc.perform(multipart("/api/cv-jobs")
                        .file(new MockMultipartFile("file", "cv.pdf", MediaType.APPLICATION_PDF_VALUE, PDF_BYTES))
                        .param("jobDescription", "Senior Java developer wanted"))
                .andExpect(status().isAccepted())
                .andReturn();
        return JsonPath.parse(result.getResponse().getContentAsString()).read("$.jobId");
    }

    private MvcResult openStream(String jobId, String lastEventId) throws Exception {
        var builder = get("/api/cv-jobs/" + jobId + "/events").accept(MediaType.TEXT_EVENT_STREAM);
        if (lastEventId != null) builder.header("Last-Event-ID", lastEventId);
        return mvc.perform(builder)
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    /** Blocks until the emitter completes (terminal event sent and stream closed). */
    private MvcResult awaitCompletion(MvcResult async) throws Exception {
        async.getAsyncResult(15_000);
        return mvc.perform(asyncDispatch(async)).andReturn();
    }

    private record Frame(long id, String name) {}

    /** Splits the SSE body into frames; comment lines (e.g. {@code :keepalive}) carry no id and are skipped. */
    private static List<Frame> parseFrames(String body) {
        var frames = new ArrayList<Frame>();
        for (String block : body.split("\n\n")) {
            Long id = null;
            String name = "message";
            for (String rawLine : block.split("\n")) {
                String line = rawLine.stripTrailing().replace("\r", "");
                if (line.startsWith("id:")) {
                    id = Long.parseLong(line.substring(3).trim());
                } else if (line.startsWith("event:")) {
                    name = line.substring(6).trim();
                }
            }
            if (id != null) frames.add(new Frame(id, name));
        }
        return frames;
    }
}
