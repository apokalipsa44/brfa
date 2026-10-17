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

    /** Hard cap for the evaluation's short description, cut at a word boundary (docs/API.md §6.4). */
    static final int MAX_SHORT_DESCRIPTION_CHARS = 200;

    /** Filled per call with the detected CV language (prompts/analyze.st LANGUAGE). */
    private static final String CV_LANGUAGE_PLACEHOLDER = "{{CV_LANGUAGE}}";

    private final ChatClient chat;
    private final String basePrompt;
    private final String jsonSchema;
    private final String reasoningEffort;
    private final int maxOutputTokens;
    private final LlmStructuredCaller caller;

    public EvaluationService(ChatClient.Builder builder, LlmStructuredCaller caller,
            @Value("classpath:/prompts/analyze.st") Resource analyzePrompt,
            @Value("classpath:/prompts/analysis.schema.json") Resource analysisSchema,
            @Value("${cvchecker.llm.reasoning-effort:none}") String reasoningEffort,
            @Value("${cvchecker.llm.max-output-tokens:4096}") int maxOutputTokens) {
        this.chat = builder.build();
        this.caller = caller;
        this.reasoningEffort = reasoningEffort;
        this.maxOutputTokens = maxOutputTokens;
        try {
            this.basePrompt = new String(analyzePrompt.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            this.jsonSchema = new String(analysisSchema.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load analyze.st / analysis.schema.json.", e);
        }
    }

    /**
     * Useful for tests and non-streaming callers.
     *
     * @param model primary model id (the analysis snapshots it at creation). When blank, the model
     *              from {@code spring.ai.openai.chat.options.model} (env {@code LLM_MODEL}) is used.
     */
    public Evaluation evaluate(String cvText, String jobDescription, String model) {
        return evaluate(cvText, jobDescription, model, null);
    }

    /**
     * @param sink streamed-output sink, or {@code null} for the blocking path
     */
    public Evaluation evaluate(String cvText, String jobDescription, String model, LlmTokenSink sink) {
        // Shared context first, instructions after: the CV + posting block is byte-identical to the
        // rewrite step's, so the backend can reuse its KV cache across the two calls (SharedContext).
        // The instruction block only changes the CV_LANGUAGE sentence, which sits after the shared
        // prefix and therefore does not disturb the cacheable tokens.
        String instructions = basePrompt.replace(CV_LANGUAGE_PLACEHOLDER, CvLanguage.instructionFor(cvText));
        String prompt = SharedContext.of(cvText, jobDescription) + instructions;
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new LlmAbortedException("Evaluation aborted: the analysis run was cancelled.");
            }
            try {
                String userPrompt = prompt + retryHint(attempt);
                OpenAiChatOptions.Builder options = jsonSchemaOptions();
                if (model != null && !model.isBlank()) {
                    options.model(model);
                }
                ChatClient.ChatClientRequestSpec request = chat.prompt().user(userPrompt).options(options);
                Evaluation evaluation = caller.call(request, Evaluation.class, sink);
                validate(evaluation);
                // Deterministic checks (prompts/analyze.st "Checks in code"): recompute the score,
                // repair the keyword lists and drop/flag suggestions the CV cannot support.
                return withCleanDescription(EvaluationSanitizer.sanitize(evaluation, cvText));
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
                || evaluation.requirements() == null
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
        return new Evaluation(evaluation.employer(), evaluation.position(), evaluation.requirements(),
                evaluation.matchScore(), evaluation.matchedKeywords(), evaluation.missingKeywords(),
                evaluation.suggestions(), evaluation.summary(), cleaned);
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
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .temperature(0.2)
                .maxTokens(maxOutputTokens)
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