package pl.example.cvchecker.llm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    private static final int MAX_CV_CHARS = 15_000;

    /** Rewrite/analysis JSON plus corrective retries must not hit LM Studio's default output cap. */
    private static final int MAX_OUTPUT_TOKENS = 8192;

    /** The user-approved suggestions plus the title context, serialized into the prompt. */
    private record RewriteContext(String employer, String position, List<Suggestion> approvedSuggestions) {
    }

    private final ChatClient chat;
    private final ObjectMapper mapper;
    private final String basePrompt;
    private final String jsonSchema;

    public CvRewriteService(ChatClient.Builder builder, ObjectMapper mapper,
            @Value("classpath:/prompts/rewrite.st") Resource rewritePrompt,
            @Value("classpath:/prompts/rewrite.schema.json") Resource rewriteSchema) {
        this.chat = builder.build();
        this.mapper = mapper;
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
        String contextJson;
        try {
            List<Suggestion> approved = approvedSuggestions == null ? List.of() : approvedSuggestions;
            contextJson = mapper.writeValueAsString(new RewriteContext(
                    blankToNull(evaluation.employer()), blankToNull(evaluation.position()), approved));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize approved suggestions for the rewrite prompt.", e);
        }
        String prompt = basePrompt
                + "\n<cv>\n" + truncate(cvText) + "\n</cv>\n<job>\n" + jobDescription + "\n</job>\n"
                + "<approved-suggestions>\n" + contextJson + "\n</approved-suggestions>";
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new LlmAbortedException("Rewrite aborted: the analysis run was cancelled.");
            }
            try {
                String userPrompt = retryHint(attempt) + prompt;
                OpenAiChatOptions.Builder options = jsonSchemaOptions();
                if (model != null && !model.isBlank()) {
                    options.model(model);
                }
                CvRewriteResult result = chat.prompt().user(userPrompt).options(options).call()
                        .entity(CvRewriteResult.class);
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
        if (result == null || result.document() == null || result.changes() == null) {
            throw new LlmInvalidOutputException("Rewrite failed validation (null document/changes).", null);
        }
        CvDocument document = result.document();
        boolean hasContent = (document.experience() != null && !document.experience().isEmpty())
                || (document.skills() != null && !document.skills().isEmpty())
                || (document.education() != null && !document.education().isEmpty());
        if (document.fullName() == null || document.fullName().isBlank() || !hasContent) {
            throw new LlmInvalidOutputException("Rewrite failed validation (empty name/content).", null);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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
        return OpenAiChatOptions.builder()
                .temperature(0.2)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .responseFormat(OpenAiChatModel.ResponseFormat.builder()
                        .type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
                        .jsonSchema(jsonSchema)
                        .strict(true)
                        .build());
    }

    private static String truncate(String text) {
        if (text != null && text.length() > MAX_CV_CHARS) {
            return text.substring(0, MAX_CV_CHARS) + "\n[truncated: CV text exceeded the model input limit]";
        }
        return text;
    }
}