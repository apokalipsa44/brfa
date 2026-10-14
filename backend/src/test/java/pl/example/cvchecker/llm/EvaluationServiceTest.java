package pl.example.cvchecker.llm;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;

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
    private EvaluationService service;

    @BeforeEach
    void setUp() {
        ChatClient chat = mock(ChatClient.class);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chat);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        call = mock(ChatClient.CallResponseSpec.class);
        when(chat.prompt()).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.options(any(OpenAiChatOptions.Builder.class))).thenReturn(request);
        when(request.call()).thenReturn(call);
        service = new EvaluationService(builder,
                new ByteArrayResource("Return JSON.".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayResource("{}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void returnsValidEvaluationOnFirstTry() {
        Evaluation good = evaluation(58);
        when(call.entity(Evaluation.class)).thenReturn(good);

        assertThat(service.evaluate("cv", "job")).isEqualTo(good);
    }

    @Test
    void retriesInvalidOutputThenSucceeds() {
        when(call.entity(Evaluation.class)).thenReturn(evaluation(150), evaluation(61));

        assertThat(service.evaluate("cv", "job").matchScore()).isEqualTo(61);
        verify(call, times(2)).entity(Evaluation.class);
    }

    @Test
    void failsAfterMaxAttempts() {
        when(call.entity(Evaluation.class)).thenReturn(evaluation(150));

        assertThatThrownBy(() -> service.evaluate("cv", "job")).isInstanceOf(LlmInvalidOutputException.class);
        verify(call, times(3)).entity(Evaluation.class);
    }

    @Test
    void retriesModelCallFailures() {
        when(call.entity(eq(Evaluation.class))).thenThrow(new RuntimeException("model loading"))
                .thenReturn(evaluation(70));

        assertThat(service.evaluate("cv", "job").matchScore()).isEqualTo(70);
    }

    @Test
    void validationRejectsBadScoresAndEmptySections() {
        assertThatThrownBy(() -> service.validate(evaluation(-1))).isInstanceOf(LlmInvalidOutputException.class);
        assertThatThrownBy(() -> service.validate(evaluation(101))).isInstanceOf(LlmInvalidOutputException.class);
        assertThatThrownBy(() -> service.validate(null)).isInstanceOf(LlmInvalidOutputException.class);
        assertThatThrownBy(() -> service.validate(new Evaluation("Acme", "Dev", 50, List.of(), List.of(), List.of(), "  ", null)))
                .isInstanceOf(LlmInvalidOutputException.class);
    }

    @Test
    void validationRejectsSuggestionWithoutProposal() {
        Evaluation broken = new Evaluation("Acme", "Dev", 50, List.of("Java"), List.of(),
                List.of(new Suggestion("Skills", "problem", "  ", "", "", Priority.HIGH)), "match", null);
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
        Evaluation dirty = new Evaluation("Acme", "Dev", 60, List.of("Java"), List.of(),
                List.of(), "ok", "   Backend role in Java.   ");
        when(call.entity(Evaluation.class)).thenReturn(dirty);

        assertThat(service.evaluate("cv", "job").shortDescription()).isEqualTo("Backend role in Java.");
    }

    private static Evaluation evaluation(int score) {
        return new Evaluation("Acme", "Senior Java Developer", score, List.of("Java"), List.of("Kubernetes"),
                List.of(new Suggestion("Skills", "Missing K8s", "Group existing container skills",
                        "Docker", "Docker, Kubernetes (basics)", Priority.MEDIUM)),
                "Solid match.", "A backend role using Java and Kubernetes.");
    }
}