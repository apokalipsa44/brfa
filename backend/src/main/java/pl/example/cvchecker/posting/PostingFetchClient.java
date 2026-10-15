package pl.example.cvchecker.posting;

import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import okhttp3.Call;
import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;

import pl.example.cvchecker.llm.LlmCallInterceptor;

/**
 * Static (no browser) fetch of a link posting (docs/API.md §4.1 step 1). SSRF-safe: the URL is
 * validated by {@link UrlGuard} up front, the resolver is UrlGuard's validated lookup (so a
 * connection can never be re-pointed by DNS rebinding), redirects are followed manually and each hop
 * is re-validated, and only {@code text/html} / {@code application/xhtml+xml} up to {@code max-bytes}
 * is accepted. The request runs under the analysis run, so {@code /cancel} aborts it.
 */
@Component
public class PostingFetchClient {

    private static final Logger log = LoggerFactory.getLogger(PostingFetchClient.class);
    private static final Set<String> HTML_TYPES = Set.of("text/html", "application/xhtml+xml");

    private final UrlGuard urlGuard;
    private final PostingFetchProperties properties;
    private final OkHttpClient client;

    public PostingFetchClient(UrlGuard urlGuard, PostingFetchProperties properties) {
        this.urlGuard = urlGuard;
        this.properties = properties;
        Dns guardedDns = hostname -> urlGuard.lookup(hostname);
        this.client = new OkHttpClient.Builder()
                .dns(guardedDns)
                .followRedirects(false)
                .retryOnConnectionFailure(false)
                .connectTimeout(properties.connectTimeout())
                .readTimeout(properties.readTimeout())
                .callTimeout(properties.totalTimeout())
                .addInterceptor(new LlmCallInterceptor())
                .build();
    }

    /** Fetches the page, following at most {@code max-redirects} re-validated redirects. */
    public FetchedPage fetch(String rawUrl) throws IOException {
        URI uri = urlGuard.validate(rawUrl);
        for (int redirects = 0; ; redirects++) {
            Response response = execute(uri);
            try {
                int code = response.code();
                if (isRedirect(code)) {
                    if (redirects >= properties.maxRedirects()) {
                        throw failed("The link redirected too many times.");
                    }
                    String location = response.header("Location");
                    if (location == null || location.isBlank()) {
                        throw failed("The link redirected without a destination.");
                    }
                    uri = urlGuard.validate(uri.resolve(location.strip()).toString());
                    continue;
                }
                if (code / 100 != 2) {
                    throw failed("The page answered HTTP " + code + ".");
                }
                requireHtml(response);
                return new FetchedPage(uri, readBodyBounded(response));
            } finally {
                response.close();
            }
        }
    }

    private Response execute(URI uri) {
        Request request = new Request.Builder()
                .url(uri.toString())
                .header("User-Agent", properties.userAgent())
                .header("Accept", "text/html,application/xhtml+xml")
                .build();
        Call call = client.newCall(request);
        try {
            return call.execute();
        } catch (UnknownHostException e) {
            throw failed("The link's host could not be resolved.");
        } catch (IOException e) {
            // Includes connect/read/call timeouts and a cancelled run.
            log.warn("Posting fetch failed for {}: {}", uri.getHost(), e.toString());
            throw failed("The page could not be fetched.");
        }
    }

    private void requireHtml(Response response) {
        MediaType type = response.body() == null ? null : response.body().contentType();
        String mime = type == null ? "" : (type.type() + "/" + type.subtype()).toLowerCase(Locale.ROOT);
        if (!HTML_TYPES.contains(mime)) {
            throw new PostingException(PostingException.ErrorCode.FETCH_NOT_HTML,
                    "The link does not point to a web page.");
        }
    }

    private String readBodyBounded(Response response) throws IOException {
        long limit = properties.maxBytes();
        ResponseBody body = response.body();
        if (body == null) {
            throw failed("The page returned no content.");
        }
        if (body.contentLength() > limit) {
            throw failed("The page is larger than the " + limit + "-byte limit.");
        }
        BufferedSource source = body.source();
        source.request(limit + 1);
        Buffer buffer = source.getBuffer();
        if (buffer.size() > limit) {
            throw failed("The page is larger than the " + limit + "-byte limit.");
        }
        Charset charset = body.contentType() != null && body.contentType().charset() != null
                ? body.contentType().charset()
                : StandardCharsets.UTF_8;
        return buffer.readString(charset);
    }

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    private static PostingException failed(String message) {
        return new PostingException(PostingException.ErrorCode.FETCH_FAILED, message);
    }
}
