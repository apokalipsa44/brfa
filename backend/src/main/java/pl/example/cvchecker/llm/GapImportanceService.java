package pl.example.cvchecker.llm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * LLM call #2 of the grouped-skills job (docs/API.md §9): scores how important each canonical skill
 * is in the context of the analysed postings (1-5) with a short rationale. Both the skills and the
 * posting context are model-generated (semi-untrusted): they travel inside delimiters and are never
 * logged.
 */
@Component
public class GapImportanceService {

    private static final Logger log = LoggerFactory.getLogger(GapImportanceService.class);

    private static final int MAX_ATTEMPTS = 3;

    private final ChatClient chat;
    private final String basePrompt;
    private final String jsonSchema;
    private final String reasoningEffort;
    private final int maxOutputTokens;
    private final Duration requestTimeout;
    private final LlmStructuredCaller caller;

    public GapImportanceService(ChatClient.Builder builder, LlmStructuredCaller caller,
            @Value("classpath:/prompts/rank-gaps.st") Resource prompt,
            @Value("classpath:/prompts/rank-gaps.schema.json") Resource schema,
            @Value("${cvchecker.llm.reasoning-effort:none}") String reasoningEffort,
            @Value("${cvchecker.skill-gaps.max-output-tokens:8192}") int maxOutputTokens,
            @Value("${cvchecker.llm.read-timeout:10m}") Duration requestTimeout) {
        this.chat = builder.build();
        this.caller = caller;
        this.reasoningEffort = reasoningEffort;
        this.maxOutputTokens = maxOutputTokens;
        this.requestTimeout = requestTimeout;
        try {
            this.basePrompt = new String(prompt.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            this.jsonSchema = new String(schema.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load rank-gaps.st / rank-gaps.schema.json.", e);
        }
    }

    /**
     * @param labels  canonical skill labels produced by {@link GapGroupingService}
     * @param postings compact posting context (one posting per line)
     * @param model   primary model id; when blank the {@code LLM_MODEL} default is used
     */
    public GapImportanceResult rank(List<String> labels, String postings, String model) {
        String prompt = basePrompt + "\n\n<skills>\n" + renderList(labels) + "</skills>\n"
                + "<postings>\n" + (postings == null ? "" : postings) + "</postings>\n";
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                OpenAiChatOptions.Builder options = jsonSchemaOptions();
                if (model != null && !model.isBlank()) {
                    options.model(model);
                }
                ChatClient.ChatClientRequestSpec request = chat.prompt()
                        .user(prompt + retryHint(attempt)).options(options);
                GapImportanceResult result = caller.call(request, GapImportanceResult.class, null);
                validate(result);
                return result;
            } catch (LlmInvalidOutputException e) {
                lastError = e;
                log.warn("Gap importance attempt {}/{} produced invalid output", attempt, MAX_ATTEMPTS);
            } catch (Exception e) {
                lastError = e;
                log.warn("Gap importance attempt {}/{} failed: {}", attempt, MAX_ATTEMPTS, e.toString());
            }
        }
        throw new LlmInvalidOutputException("LLM returned invalid gap importance after " + MAX_ATTEMPTS + " attempts.",
                lastError);
    }

    private static String renderList(List<String> labels) {
        StringBuilder builder = new StringBuilder();
        for (String label : labels) {
            if (label == null || label.isBlank()) continue;
            builder.append("- ").append(label.strip().replaceAll("\\s+", " ")).append('\n');
        }
        return builder.toString();
    }

    void validate(GapImportanceResult result) {
        if (result == null || result.skills() == null
                || result.skills().stream().anyMatch(s -> s == null || blank(s.label()))) {
            throw new LlmInvalidOutputException("Gap importance failed validation.", null);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String retryHint(int attempt) {
        return attempt >= 2
                ? "Your previous response was rejected: it was not a single valid JSON object. "
                + "Respond again with ONLY the JSON object described above. No prose, no markdown, no code fences.\n\n"
                : "";
    }

    private OpenAiChatOptions.Builder jsonSchemaOptions() {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .temperature(0.2)
                .maxTokens(maxOutputTokens)
                .timeout(requestTimeout)
                .responseFormat(OpenAiChatModel.ResponseFormat.builder()
                        .type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
                        .jsonSchema(jsonSchema)
                        .strict(true)
                        .build());
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            builder.reasoningEffort(reasoningEffort);
        }
        return builder;
    }
}
