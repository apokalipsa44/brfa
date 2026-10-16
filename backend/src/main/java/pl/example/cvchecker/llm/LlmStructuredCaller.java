package pl.example.cvchecker.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * Runs one structured-output call against the ChatClient, either blocking ({@code null} sink, using
 * Spring AI's entity conversion) or streaming (deltas forwarded to the sink, JSON accumulated and
 * parsed here). Streaming is used only for the user-facing long steps; LM Studio enforces the JSON
 * schema at generation time, so the concatenated output is a single JSON object.
 */
@Component
public class LlmStructuredCaller {

    private final ObjectMapper mapper;

    public LlmStructuredCaller(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param request the fully-built request (prompt + options)
     * @param type    the record to deserialize the JSON into
     * @param sink    token sink, or {@code null} to use the blocking entity path
     */
    public <T> T call(ChatClient.ChatClientRequestSpec request, Class<T> type, LlmTokenSink sink) {
        if (sink == null) {
            return request.call().entity(type);
        }
        StringBuilder buffer = new StringBuilder();
        request.stream().content()
                .doOnNext(chunk -> {
                    buffer.append(chunk);
                    sink.onToken(chunk);
                })
                .blockLast();
        return parse(buffer.toString(), type);
    }

    private <T> T parse(String raw, Class<T> type) {
        String json = stripFences(raw);
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new LlmInvalidOutputException("Streamed LLM output was not valid JSON.", e);
        }
    }

    /** Belt-and-braces: the strict schema should already prevent markdown fences around the JSON. */
    private static String stripFences(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1).strip();
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3).strip();
            }
        }
        return text;
    }
}
