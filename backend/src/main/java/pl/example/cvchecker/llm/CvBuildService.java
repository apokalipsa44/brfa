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

import tools.jackson.databind.ObjectMapper;

/**
 * LLM step for the standalone CV builder (docs/API.md §10): reshapes the active CV into an
 * ATS-formatted document and adds the skills the user selected — nothing else. Independent of the
 * job-analysis rewrite: there is no posting and no approved suggestions here. CV text is untrusted
 * input: delimited, never logged.
 */
@Component
public class CvBuildService {

    private static final Logger log = LoggerFactory.getLogger(CvBuildService.class);

    /** Max attempts: initial call + 2 retries on invalid structured output (plan §6.7). */
    private static final int MAX_ATTEMPTS = 3;

    private final ChatClient chat;
    private final ObjectMapper mapper;
    private final String basePrompt;
    private final String jsonSchema;
    private final String reasoningEffort;
    private final int maxOutputTokens;
    private final Duration requestTimeout;
    private final LlmStructuredCaller caller;

    public CvBuildService(ChatClient.Builder builder, ObjectMapper mapper, LlmStructuredCaller caller,
            @Value("classpath:/prompts/build-cv.st") Resource buildPrompt,
            @Value("classpath:/prompts/rewrite.schema.json") Resource rewriteSchema,
            @Value("${cvchecker.llm.reasoning-effort:none}") String reasoningEffort,
            @Value("${cvchecker.llm.max-output-tokens:4096}") int maxOutputTokens,
            @Value("${cvchecker.llm.read-timeout:10m}") Duration requestTimeout) {
        this.chat = builder.build();
        this.mapper = mapper;
        this.caller = caller;
        this.reasoningEffort = reasoningEffort;
        this.maxOutputTokens = maxOutputTokens;
        this.requestTimeout = requestTimeout;
        try {
            this.basePrompt = new String(buildPrompt.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            this.jsonSchema = new String(rewriteSchema.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load build-cv.st / rewrite.schema.json.", e);
        }
    }

    /**
     * @param skills the skills the user selected to add (already trimmed/de-duplicated)
     * @param model  primary model id, or blank/null for the configured default
     * @param sink   streamed-output sink, or {@code null} for the blocking path
     */
    public CvRewriteResult build(String cvText, List<String> skills, String model, LlmTokenSink sink) {
        List<String> selected = skills == null ? List.of() : skills;
        String skillsJson;
        try {
            skillsJson = mapper.writeValueAsString(selected);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize the selected skills for the build prompt.", e);
        }
        String prompt = SharedContext.cvOnly(cvText) + basePrompt
                + "\n<skills-to-add>\n" + skillsJson + "\n</skills-to-add>\n";
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new LlmAbortedException("CV build aborted: the run was cancelled.");
            }
            try {
                String userPrompt = prompt + retryHint(attempt);
                OpenAiChatOptions.Builder options = jsonSchemaOptions();
                if (model != null && !model.isBlank()) {
                    options.model(model);
                }
                CvRewriteResult result = caller.call(
                        chat.prompt().user(userPrompt).options(options), CvRewriteResult.class, sink);
                CvRewriteValidator.validate(result);
                return result;
            } catch (LlmInvalidOutputException e) {
                lastError = e;
                log.warn("LLM build attempt {}/{} produced invalid output", attempt, MAX_ATTEMPTS);
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new LlmAbortedException("CV build aborted: the run was cancelled.");
                }
                lastError = e;
                log.warn("LLM build attempt {}/{} failed: {}", attempt, MAX_ATTEMPTS, e.toString());
            }
        }
        throw new LlmInvalidOutputException("LLM returned invalid CV build after " + MAX_ATTEMPTS + " attempts.",
                lastError);
    }

    /** Attempts ≥ 2 get a corrective hint (plan §6.7): the model must re-emit truly minimal JSON. */
    private static String retryHint(int attempt) {
        return attempt >= 2
                ? "Your previous response was rejected: it was not a single valid JSON object. "
                + "Respond again with ONLY the JSON object described above. No prose, no markdown, no code fences.\n\n"
                : "";
    }

    /**
     * LM Studio enforces the schema at generation time (docs/ENVIRONMENT.md), so the response cannot be
     * structurally invalid; validation + retry remain as the safety net (plan §6.7).
     */
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
