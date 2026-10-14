package pl.example.cvchecker.llm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * LLM step #1: scores the original CV against the posting and extracts employer/position for the
 * auto-title (docs/API.md §6.2). CV and posting text are untrusted input: they travel inside clear
 * delimiters and are never logged (plan §6.9, AGENTS.md).
 */
@Component
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    /** Max attempts: initial call + 2 retries on invalid structured output (plan §6.7). */
    private static final int MAX_ATTEMPTS = 3;

    /** Long inputs blow the local model's context window, so the CV side is truncated (plan §6.3.4). */
    private static final int MAX_CV_CHARS = 15_000;

    /** Rewrite/analysis JSON plus corrective retries must not hit LM Studio's default output cap. */
    private static final int MAX_OUTPUT_TOKENS = 8192;

    /** Hard cap for the evaluation's short description, cut at a word boundary (docs/API.md §6.4). */
    static final int MAX_SHORT_DESCRIPTION_CHARS = 200;

    private final ChatClient chat;
    private final String basePrompt;
    private final String jsonSchema;

    public EvaluationService(ChatClient.Builder builder,
            @Value("classpath:/prompts/analyze.st") Resource analyzePrompt,
            @Value("classpath:/prompts/analysis.schema.json") Resource analysisSchema) {
        this.chat = builder.build();
        try {
            this.basePrompt = new String(analyzePrompt.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            this.jsonSchema = new String(analysisSchema.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load analyze.st / analysis.schema.json.", e);
        }
    }

    public Evaluation evaluate(String cvText, String jobDescription) {
        return evaluate(cvText, jobDescription, null);
    }

    /**
     * @param model primary model id (the analysis snapshots it at creation). When blank, the model
     *              from {@code spring.ai.openai.chat.options.model} (env {@code LLM_MODEL}) is used.
     */
    public Evaluation evaluate(String cvText, String jobDescription, String model) {
        String prompt = basePrompt
                + "\n<cv>\n" + truncate(cvText) + "\n</cv>\n<job>\n" + jobDescription + "\n</job>";
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new LlmAbortedException("Evaluation aborted: the analysis run was cancelled.");
            }
            try {
                String userPrompt = retryHint(attempt) + prompt;
                OpenAiChatOptions.Builder options = jsonSchemaOptions();
                if (model != null && !model.isBlank()) {
                    options.model(model);
                }
                Evaluation evaluation = chat.prompt().user(userPrompt).options(options).call()
                        .entity(Evaluation.class);
                validate(evaluation);
                return withCleanDescription(evaluation);
            } catch (LlmInvalidOutputException e) {
                lastError = e;
                log.warn("LLM evaluation attempt {}/{} produced invalid output", attempt, MAX_ATTEMPTS);
            } catch (Exception e) {
                // A cancelled analysis aborts the call: stop at once instead of retrying.
                if (Thread.currentThread().isInterrupted()) {
                    throw new LlmAbortedException("Evaluation aborted: the analysis run was cancelled.");
                }
                // Call/conversion failure from Spring AI or the model: retry as well; a cold local
                // model often fails the first request while loading.
                lastError = e;
                log.warn("LLM evaluation attempt {}/{} failed: {}", attempt, MAX_ATTEMPTS, e.toString());
            }
        }
        throw new LlmInvalidOutputException("LLM returned invalid evaluation after " + MAX_ATTEMPTS + " attempts.",
                lastError);
    }

    /** Rejects out-of-range scores and empty sections so bad JSON never reaches the pipeline. */
    void validate(Evaluation evaluation) {
        if (evaluation == null
                || evaluation.matchScore() < 0 || evaluation.matchScore() > 100
                || evaluation.matchedKeywords() == null
                || evaluation.missingKeywords() == null
                || evaluation.suggestions() == null
                || evaluation.summary() == null || evaluation.summary().isBlank()
                || evaluation.suggestions().stream()
                        .anyMatch(s -> s == null || blank(s.section()) || blank(s.problem())
                                || blank(s.proposal()) || s.priority() == null)) {
            throw new LlmInvalidOutputException("Evaluation failed validation (score range / empty sections).", null);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** Returns the evaluation unchanged when its description is already clean, or a capped copy. */
    static Evaluation withCleanDescription(Evaluation evaluation) {
        String cleaned = cleanShortDescription(evaluation.shortDescription());
        if (java.util.Objects.equals(cleaned, evaluation.shortDescription())) {
            return evaluation;
        }
        return new Evaluation(evaluation.employer(), evaluation.position(), evaluation.matchScore(),
                evaluation.matchedKeywords(), evaluation.missingKeywords(), evaluation.suggestions(),
                evaluation.summary(), cleaned);
    }

    /**
     * Trims the short description and enforces the hard character cap, cutting at a word boundary and
     * appending an ellipsis (docs/API.md §6.4). A null or blank value becomes null.
     */
    static String cleanShortDescription(String value) {
        if (blank(value)) return null;
        String trimmed = value.strip();
        if (trimmed.length() <= MAX_SHORT_DESCRIPTION_CHARS) return trimmed;
        int limit = MAX_SHORT_DESCRIPTION_CHARS - 1; // room for the ellipsis
        String cut = trimmed.substring(0, limit);
        int lastSpace = cut.lastIndexOf(' ');
        if (lastSpace > 0) {
            cut = cut.substring(0, lastSpace);
        }
        return cut.stripTrailing() + "…";
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