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
 * LLM call #1 of the grouped-skills job (docs/API.md §9): folds the flat list of gap phrasings into
 * canonical skills, translating them to English and merging synonyms across languages. The terms are
 * model-generated (semi-untrusted): they travel inside {@code <gaps>} delimiters and are never logged.
 */
@Component
public class GapGroupingService {

    private static final Logger log = LoggerFactory.getLogger(GapGroupingService.class);

    private static final int MAX_ATTEMPTS = 3;

    /** Cap the number of terms sent to the model; beyond this the overview is already fuzzy. */
    static final int MAX_TERMS = 250;

    private final ChatClient chat;
    private final String basePrompt;
    private final String jsonSchema;
    private final String reasoningEffort;
    private final int maxOutputTokens;
    private final Duration requestTimeout;
    private final LlmStructuredCaller caller;

    public GapGroupingService(ChatClient.Builder builder, LlmStructuredCaller caller,
            @Value("classpath:/prompts/group-gaps.st") Resource prompt,
            @Value("classpath:/prompts/group-gaps.schema.json") Resource schema,
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
            throw new IllegalStateException("Cannot load group-gaps.st / group-gaps.schema.json.", e);
        }
    }

    /**
     * @param terms distinct gap phrasings (possibly Polish)
     * @param model primary model id; when blank the {@code LLM_MODEL} default is used
     */
    public GapGroupingResult group(List<String> terms, String model) {
        String prompt = basePrompt + "\n\n<gaps>\n" + renderTerms(terms) + "</gaps>\n";
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                OpenAiChatOptions.Builder options = jsonSchemaOptions();
                if (model != null && !model.isBlank()) {
                    options.model(model);
                }
                ChatClient.ChatClientRequestSpec request = chat.prompt()
                        .user(prompt + retryHint(attempt)).options(options);
                GapGroupingResult result = caller.call(request, GapGroupingResult.class, null);
                validate(result);
                return result;
            } catch (LlmInvalidOutputException e) {
                lastError = e;
                log.warn("Gap grouping attempt {}/{} produced invalid output", attempt, MAX_ATTEMPTS);
            } catch (Exception e) {
                lastError = e;
                log.warn("Gap grouping attempt {}/{} failed: {}", attempt, MAX_ATTEMPTS, e.toString());
            }
        }
        throw new LlmInvalidOutputException("LLM returned invalid gap grouping after " + MAX_ATTEMPTS + " attempts.",
                lastError);
    }

    private static String renderTerms(List<String> terms) {
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (String term : terms) {
            if (term == null || term.isBlank()) continue;
            if (count++ >= MAX_TERMS) break;
            builder.append("- ").append(term.strip().replaceAll("\\s+", " ")).append('\n');
        }
        return builder.toString();
    }

    void validate(GapGroupingResult result) {
        if (result == null || result.groups() == null
                || result.groups().stream().anyMatch(g -> g == null || blank(g.label()) || g.members() == null)) {
            throw new LlmInvalidOutputException("Gap grouping failed validation.", null);
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
