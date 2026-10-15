package pl.example.cvchecker.analysis;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import okhttp3.OkHttpClient;
import okhttp3.Request;

import pl.example.cvchecker.llm.LlmCallInterceptor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cancellation actually aborts a blocking HTTP call (docs/DECISIONS.md). A thread interrupt alone
 * does not break a synchronous OkHttp read, so {@link RunHandle#cancel()} must cancel the registered
 * {@link okhttp3.Call}; this exercises that contract against a deliberately slow server.
 */
class RunHandleTest {

    private HttpServer server;
    private final CountDownLatch releaseHandler = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        releaseHandler.countDown();
        if (server != null) {
            server.stop(0);
        }
        RunHandle.clearCurrent();
    }

    @Test
    void cancelAbortsABlockingCallThroughTheInterceptor() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            try {
                releaseHandler.await(); // hold the response open until the test releases it
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/slow";

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new LlmCallInterceptor())
                .callTimeout(Duration.ofSeconds(30))
                .build();

        RunHandle handle = new RunHandle();
        AtomicBoolean aborted = new AtomicBoolean(false);
        AtomicReference<IOException> failure = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            RunHandle.setCurrent(handle);
            try {
                client.newCall(new Request.Builder().url(url).build()).execute().close();
            } catch (IOException e) {
                failure.set(e);
                aborted.set(true);
            } finally {
                RunHandle.clearCurrent();
            }
        });
        worker.start();

        Thread.sleep(300); // let the worker block on the socket read
        long started = System.nanoTime();
        handle.cancel();
        worker.join(TimeUnit.SECONDS.toMillis(3));

        assertThat(worker.isAlive()).as("worker must be freed by cancel()").isFalse();
        assertThat(aborted.get()).as("the blocking call must fail").isTrue();
        assertThat(failure.get().getMessage()).isNotBlank();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }
}
