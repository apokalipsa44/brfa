package pl.example.cvchecker.llm;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvaluationServiceTest {

    private ChatClient.CallResponseSpec call;
    private ChatClient.ChatClientRequestSpec request;
    private EvaluationService service;

    @BeforeEach
    void setUp() {
        ChatClient chat = mock(ChatClient.class);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chat);
        request = mock(ChatClient.ChatClientRequestSpec.class);
        call = mock(ChatClient.CallResponseSpec.class);
        when(chat.prompt()).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.options(any(OpenAiChatOptions.Builder.class))).thenReturn(request);
        when(request.call()).thenReturn(call);
        service = new EvaluationService(builder, new LlmStructuredCaller(new ObjectMapper()),
                new ByteArrayResource("Return JSON.".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayResource("{}".getBytes(StandardCharsets.UTF_8)),
                "none", 4096);
    }

    @Test
    void disablesModelThinkingWithReasoningEffortNone() {
        when(call.entity(Evaluation.class)).thenReturn(evaluation(60));

        service.evaluate("cv", "job", null);

        ArgumentCaptor<OpenAiChatOptions.Builder> captor = ArgumentCaptor.forClass(OpenAiChatOptions.Builder.class);
        verify(request).options(captor.capture());
        assertThat(captor.getValue().build().getReasoningEffort()).isEqualTo("none");
        assertThat(captor.getValue().build().getMaxTokens()).isEqualTo(4096);
    }

    @Test
    void returnsValidEvaluationOnFirstTry() {
        Evaluation good = evaluation(58);
        when(call.entity(Evaluation.class)).thenReturn(good);

        assertThat(service.evaluate("Docker Kubernetes", "job", null)).isEqualTo(good);
    }

    @Test
    void sendsTheSharedContextAsThePromptPrefix() {
        when(call.entity(Evaluation.class)).thenReturn(evaluation(60));

        service.evaluate("my cv", "my job", null);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(request).user(captor.capture());
        assertThat(captor.getValue()).startsWith(SharedContext.of("my cv", "my job"));
    }

    @Test
    void retriesInvalidOutputThenSucceeds() {
        when(call.entity(Evaluation.class)).thenReturn(evaluation(150), evaluation(61));

        assertThat(service.evaluate("cv", "job", null).matchScore()).isEqualTo(61);
        verify(call, times(2)).entity(Evaluation.class);
    }

    @Test
    void failsAfterMaxAttempts() {
        when(call.entity(Evaluation.class)).thenReturn(evaluation(150));

        assertThatThrownBy(() -> service.evaluate("cv", "job", null)).isInstanceOf(LlmInvalidOutputException.class);
        verify(call, times(3)).entity(Evaluation.class);
    }

    @Test
    void retriesModelCallFailures() {
        when(call.entity(eq(Evaluation.class))).thenThrow(new RuntimeException("model loading"))
                .thenReturn(evaluation(70));

        assertThat(service.evaluate("cv", "job", null).matchScore()).isEqualTo(70);
    }

    @Test
    void validationRejectsBadScoresAndEmptySections() {
        assertThatThrownBy(() -> service.validate(evaluation(-1))).isInstanceOf(LlmInvalidOutputException.class);
        assertThatThrownBy(() -> service.validate(evaluation(101))).isInstanceOf(LlmInvalidOutputException.class);
        assertThatThrownBy(() -> service.validate(null)).isInstanceOf(LlmInvalidOutputException.class);
        assertThatThrownBy(() -> service.validate(new Evaluation("Acme", "Dev", List.of(), 50, List.of(), List.of(), List.of(), "  ", null)))
                .isInstanceOf(LlmInvalidOutputException.class);
    }

    @Test
    void validationRejectsSuggestionWithoutProposal() {
        Evaluation broken = new Evaluation("Acme", "Dev", List.of(), 50, List.of("Java"), List.of(),
                List.of(new Suggestion("Skills", "problem", "  ", "", "", Priority.HIGH, false)), "match", null);
        assertThatThrownBy(() -> service.validate(broken)).isInstanceOf(LlmInvalidOutputException.class);
    }

    @Test
    void shortDescriptionIsTrimmedAndCappedAtAWordBoundary() {
        assertThat(EvaluationService.cleanShortDescription("  hello  ")).isEqualTo("hello");
        assertThat(EvaluationService.cleanShortDescription("   ")).isNull();
        assertThat(EvaluationService.cleanShortDescription(null)).isNull();

        String long_ = "word ".repeat(80).strip(); // 399 chars
        String capped = EvaluationService.cleanShortDescription(long_);
        assertThat(capped).hasSizeLessThanOrEqualTo(200);
        assertThat(capped).endsWith("…");
        assertThat(capped).doesNotContain("  ");
    }

    @Test
    void evaluateReturnsACleanedShortDescription() {
        Evaluation dirty = new Evaluation("Acme", "Dev", List.of(), 60, List.of("Java"), List.of(),
                List.of(), "ok", "   Backend role in Java.   ");
        when(call.entity(Evaluation.class)).thenReturn(dirty);

        assertThat(service.evaluate("cv", "job", null).shortDescription()).isEqualTo("Backend role in Java.");
    }

    private static Evaluation evaluation(int score) {
        return new Evaluation("Acme", "Senior Java Developer", List.of(), score, List.of("Java"), List.of("Terraform"),
                List.of(new Suggestion("Skills", "Missing K8s", "Group existing container skills",
                        "Docker", "Docker Kubernetes", Priority.MEDIUM, false)),
                "Solid match.", "A backend role using Java and Kubernetes.");
    }
}