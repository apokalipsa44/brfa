package pl.example.cvchecker.posting;

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

import pl.example.cvchecker.llm.LlmAbortedException;
import pl.example.cvchecker.llm.LlmInvalidOutputException;

/**
 * One structured-output LLM call that turns extracted page text into a clean job posting and decides
 * whether the page is a job posting at all (docs/API.md §4.1 step 3). Used for HTML-extracted text
 * only; JSON-LD data is trusted and skips this call. Page text is untrusted input: delimited and
 * never logged.
 */
@Component
public class PostingCleanupService {

    private static final Logger log = LoggerFactory.getLogger(PostingCleanupService.class);

    /** Max attempts: initial call + 2 retries on invalid structured output (plan §6.7). */
    private static final int MAX_ATTEMPTS = 3;

    /** Pages can be large; keep the cleanup prompt inside the local model's context window. */
    private static final int MAX_PAGE_CHARS = 20_000;

    private static final int MAX_OUTPUT_TOKENS = 8192;

    private final ChatClient chat;
    private final String basePrompt;
    private final String jsonSchema;

    public PostingCleanupService(ChatClient.Builder builder,
            @Value("classpath:/prompts/posting.st") Resource postingPrompt,
            @Value("classpath:/prompts/posting.schema.json") Resource postingSchema) {
        this.chat = builder.build();
        try {
            this.basePrompt = new String(postingPrompt.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            this.jsonSchema = new String(postingSchema.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load posting.st / posting.schema.json.", e);
        }
    }

    /**
     * @param pageText readable text extracted from the page
     * @param model    the analysis' model id (snapshotted at creation), or blank for the default
     */
    public PostingExtraction cleanup(String pageText, String model) {
        String prompt = basePrompt + "\n<page>\n" + truncate(pageText) + "\n</page>";
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new LlmAbortedException("Posting cleanup aborted: the analysis run was cancelled.");
            }
            try {
                OpenAiChatOptions.Builder options = jsonSchemaOptions();
                if (model != null && !model.isBlank()) {
                    options.model(model);
                }
                PostingExtraction extraction = chat.prompt().user(retryHint(attempt) + prompt).options(options)
                        .call().entity(PostingExtraction.class);
                validate(extraction);
                return extraction;
            } catch (LlmInvalidOutputException e) {
                lastError = e;
                log.warn("Posting cleanup attempt {}/{} produced invalid output", attempt, MAX_ATTEMPTS);
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new LlmAbortedException("Posting cleanup aborted: the analysis run was cancelled.");
                }
                lastError = e;
                log.warn("Posting cleanup attempt {}/{} failed: {}", attempt, MAX_ATTEMPTS, e.toString());
            }
        }
        throw new LlmInvalidOutputException("LLM returned invalid posting cleanup after " + MAX_ATTEMPTS
                + " attempts.", lastError);
    }

    void validate(PostingExtraction extraction) {
        if (extraction == null) {
            throw new LlmInvalidOutputException("Posting cleanup returned no JSON object.", null);
        }
    }

    private static String retryHint(int attempt) {
        return attempt >= 2
                ? "Your previous response was rejected: it was not a single valid JSON object. "
                + "Respond again with ONLY the JSON object described above. No prose, no markdown, no code fences.\n\n"
                : "";
    }

    private OpenAiChatOptions.Builder jsonSchemaOptions() {
        return OpenAiChatOptions.builder()
                .temperature(0.1)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .responseFormat(OpenAiChatModel.ResponseFormat.builder()
                        .type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
                        .jsonSchema(jsonSchema)
                        .strict(true)
                        .build());
    }

    private static String truncate(String text) {
        if (text != null && text.length() > MAX_PAGE_CHARS) {
            return text.substring(0, MAX_PAGE_CHARS) + "\n[truncated: page text exceeded the model input limit]";
        }
        return text;
    }
}
