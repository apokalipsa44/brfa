package pl.example.cvchecker.llm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
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

    private final ChatClient chat;
    private final String basePrompt;

    public CvAnalysisService(ChatClient.Builder builder,
            @Value("classpath:/prompts/analyze.st") Resource analyzePrompt) {
        this.chat = builder.build();
        try {
            this.basePrompt = new String(analyzePrompt.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load analyze.st prompt template.", e);
        }
    }

    public CvAnalysis analyze(String cvText, String jobDescription) {
        String prompt = basePrompt
                + "\n<cv>\n" + truncate(cvText) + "\n</cv>\n<job>\n" + jobDescription + "\n</job>";
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                CvAnalysis analysis = chat.prompt().user(prompt).call().entity(CvAnalysis.class);
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

    private static String truncate(String text) {
        if (text != null && text.length() > MAX_CV_CHARS) {
            return text.substring(0, MAX_CV_CHARS) + "\n[truncated: CV text exceeded the model input limit]";
        }
        return text;
    }
}
