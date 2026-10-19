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
 * LLM step #2: rewrites the CV from its text, the posting and the suggestions the user approved
 * (docs/API.md §6.1: the rewrite must apply only those — nothing else). CV/posting text is untrusted
 * input: delimited, never logged.
 */
@Component
public class CvRewriteService {

    private static final Logger log = LoggerFactory.getLogger(CvRewriteService.class);

    /** Max attempts: initial call + 2 retries on invalid structured output (plan §6.7). */
    private static final int MAX_ATTEMPTS = 3;

    /** The user-approved suggestions plus the title context, serialized into the prompt. */
    private record RewriteContext(String employer, String position, List<Suggestion> approvedSuggestions) {
    }

    private final ChatClient chat;
    private final ObjectMapper mapper;
    private final String basePrompt;
    private final String jsonSchema;
    private final String reasoningEffort;
    private final int maxOutputTokens;
    private final Duration requestTimeout;
    private final LlmStructuredCaller caller;

    public CvRewriteService(ChatClient.Builder builder, ObjectMapper mapper, LlmStructuredCaller caller,
            @Value("classpath:/prompts/rewrite.st") Resource rewritePrompt,
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
            this.basePrompt = new String(rewritePrompt.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            this.jsonSchema = new String(rewriteSchema.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load rewrite.st / rewrite.schema.json.", e);
        }
    }

    /**
     * @param evaluation          step-1 {@link Evaluation} whose employer/position give the title context
     * @param approvedSuggestions the user-approved suggestions; only these may be applied
     * @param model               primary model id (snapshotted at analysis creation), or blank for the default
     */
    public CvRewriteResult rewrite(String cvText, String jobDescription, Evaluation evaluation,
            List<Suggestion> approvedSuggestions, String model) {
        return rewrite(cvText, jobDescription, evaluation, approvedSuggestions, model, null);
    }

    /**
     * @param sink streamed-output sink, or {@code null} for the blocking path
     */
    public CvRewriteResult rewrite(String cvText, String jobDescription, Evaluation evaluation,
            List<Suggestion> approvedSuggestions, String model, LlmTokenSink sink) {
        String contextJson;
        List<Suggestion> approved = approvedSuggestions == null ? List.of() : approvedSuggestions;
        try {
            contextJson = mapper.writeValueAsString(new RewriteContext(
                    blankToNull(evaluation.employer()), blankToNull(evaluation.position()), approved));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize approved suggestions for the rewrite prompt.", e);
        }
        // Shared context first (byte-identical to the evaluation step's) so the backend can reuse its
        // KV cache across the two calls; the step-specific instructions and suggestions follow.
        String prompt = SharedContext.of(cvText, jobDescription) + basePrompt
                + "\n<approved-suggestions>\n" + contextJson + "\n</approved-suggestions>"
                + noChangesDirective(approved);
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new LlmAbortedException("Rewrite aborted: the analysis run was cancelled.");
            }
            try {
                String userPrompt = prompt + retryHint(attempt);
                OpenAiChatOptions.Builder options = jsonSchemaOptions();
                if (model != null && !model.isBlank()) {
                    options.model(model);
                }
                CvRewriteResult result = caller.call(
                        chat.prompt().user(userPrompt).options(options), CvRewriteResult.class, sink);
                validate(result);
                return result;
            } catch (LlmInvalidOutputException e) {
                lastError = e;
                log.warn("LLM rewrite attempt {}/{} produced invalid output", attempt, MAX_ATTEMPTS);
            } catch (Exception e) {
                // A cancelled analysis aborts the call: stop at once instead of retrying.
                if (Thread.currentThread().isInterrupted()) {
                    throw new LlmAbortedException("Rewrite aborted: the analysis run was cancelled.");
                }
                lastError = e;
                log.warn("LLM rewrite attempt {}/{} failed: {}", attempt, MAX_ATTEMPTS, e.toString());
            }
        }
        throw new LlmInvalidOutputException("LLM returned invalid rewrite after " + MAX_ATTEMPTS + " attempts.",
                lastError);
    }

    /** Rejects empty documents so bad JSON never reaches the PDF renderer. */
    void validate(CvRewriteResult result) {
        CvRewriteValidator.validate(result);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * With no approved suggestions the rewrite must be a pure ATS normalization (docs/API.md §4):
     * the analyze prompt may return an empty list, and the "generate ATS-formatted version" path
     * must not smuggle in content edits driven by the posting. Only the formatting rules from
     * rewrite.st (headline, category-prefixed skills, dates, single-column bullets) may apply.
     */
    private static String noChangesDirective(List<Suggestion> approved) {
        if (!approved.isEmpty()) {
            return "";
        }
        return "\n\nThere are NO approved suggestions: apply none. Keep the CV's wording, order and "
                + "facts exactly as in the original and do not add keywords from the job posting. Only "
                + "the ATS typesetting normalizations from the rules above (HEADLINE, category-prefixed "
                + "SKILLS, consistent dates, single-column bullets) may change; list just those in `changes`.";
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
        // Turning "thinking" off is the single biggest latency win for reasoning-capable local models
        // (docs/DECISIONS.md): the structured JSON is short, so hidden reasoning tokens are pure cost.
        // The builder mutates in place, so the fluent return value is intentionally ignored.
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            builder.reasoningEffort(reasoningEffort);
        }
        return builder;
    }
}