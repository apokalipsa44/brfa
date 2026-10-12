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
 * LLM step #1: scores the original CV against the posting (plan §6.7).
 * CV and posting text are untrusted input: they travel inside clear delimiters
 * and are never logged (plan §6.9, AGENTS.md).
 */
@Component
public class CvAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(CvAnalysisService.class);

    /** Max attempts: initial call + 2 retries on invalid structured output (plan §6.7). */
    private static final int MAX_ATTEMPTS = 3;

    /** Long inputs blow the local model's context window, so the CV side is truncated (plan §6.3.4). */
    private static final int MAX_CV_CHARS = 15_000;

    /** Rewrite/analysis JSON plus corrective retries must not hit LM Studio's default output cap. */
    private static final int MAX_OUTPUT_TOKENS = 8192;

    private final ChatClient chat;
    private final String basePrompt;
    private final String jsonSchema;

    public CvAnalysisService(ChatClient.Builder builder,
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

    public CvAnalysis analyze(String cvText, String jobDescription) {
        return analyze(cvText, jobDescription, null);
    }

    /**
     * @param model optional per-job model override (frontend dropdown). When blank, the model from
     *              {@code spring.ai.openai.chat.options.model} (env {@code LLM_MODEL}) is used.
     */
    public CvAnalysis analyze(String cvText, String jobDescription, String model) {
        String prompt = basePrompt
                + "\n<cv>\n" + truncate(cvText) + "\n</cv>\n<job>\n" + jobDescription + "\n</job>";
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String userPrompt = retryHint(attempt) + prompt;
                OpenAiChatOptions.Builder options = jsonSchemaOptions();
                if (model != null && !model.isBlank()) {
                    options.model(model);
                }
                CvAnalysis analysis = chat.prompt().user(userPrompt).options(options).call()
                        .entity(CvAnalysis.class);
                validate(analysis);
                return analysis;
            } catch (LlmInvalidOutputException e) {
                lastError = e;
                log.warn("LLM analysis attempt {}/{} produced invalid output", attempt, MAX_ATTEMPTS);
            } catch (Exception e) {
                // Call/conversion failure from Spring AI or the model: retry as well; a cold local
                // model often fails the first request while loading.
                lastError = e;
                log.warn("LLM analysis attempt {}/{} failed: {}", attempt, MAX_ATTEMPTS, e.toString());
            }
        }
        throw new LlmInvalidOutputException("LLM returned invalid analysis after " + MAX_ATTEMPTS + " attempts.",
                lastError);
    }

    /** Rejects out-of-range scores and empty sections so bad JSON never reaches the pipeline. */
    void validate(CvAnalysis analysis) {
        if (analysis == null
                || analysis.matchScore() < 0 || analysis.matchScore() > 100
                || analysis.matchedKeywords() == null
                || analysis.missingKeywords() == null
                || analysis.suggestions() == null
                || analysis.summary() == null || analysis.summary().isBlank()) {
            throw new LlmInvalidOutputException("Analysis failed validation (score range / empty sections).", null);
        }
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
