package pl.example.cvchecker.llm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * LLM step #2: rewrites the CV from its text, the posting and the analysis
 * (plan §6.7). CV/posting text is untrusted input: delimited, never logged.
 */
@Component
public class CvRewriteService {

    private static final Logger log = LoggerFactory.getLogger(CvRewriteService.class);

    /** Max attempts: initial call + 2 retries on invalid structured output (plan §6.7). */
    private static final int MAX_ATTEMPTS = 3;

    private static final int MAX_CV_CHARS = 15_000;

    private final ChatClient chat;
    private final ObjectMapper mapper;
    private final String basePrompt;

    public CvRewriteService(ChatClient.Builder builder, ObjectMapper mapper,
            @Value("classpath:/prompts/rewrite.st") Resource rewritePrompt) {
        this.chat = builder.build();
        this.mapper = mapper;
        try {
            this.basePrompt = new String(rewritePrompt.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load rewrite.st prompt template.", e);
        }
    }

    public CvRewriteResult rewrite(String cvText, String jobDescription, CvAnalysis analysis) {
        String analysisJson;
        try {
            analysisJson = mapper.writeValueAsString(analysis);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize CvAnalysis for the rewrite prompt.", e);
        }
        String prompt = basePrompt
                + "\n<cv>\n" + truncate(cvText) + "\n</cv>\n<job>\n" + jobDescription + "\n</job>\n<analysis>\n"
                + analysisJson + "\n</analysis>";
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                CvRewriteResult result = chat.prompt().user(prompt).call().entity(CvRewriteResult.class);
                validate(result);
                return result;
            } catch (LlmInvalidOutputException e) {
                lastError = e;
                log.warn("LLM rewrite attempt {}/{} produced invalid output", attempt, MAX_ATTEMPTS);
            } catch (Exception e) {
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

    private static String truncate(String text) {
        if (text != null && text.length() > MAX_CV_CHARS) {
            return text.substring(0, MAX_CV_CHARS) + "\n[truncated: CV text exceeded the model input limit]";
        }
        return text;
    }
}
