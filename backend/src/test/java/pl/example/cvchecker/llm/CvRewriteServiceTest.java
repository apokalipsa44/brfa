package pl.example.cvchecker.llm;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

class CvRewriteServiceTest {

    private ChatClient.CallResponseSpec call;
    private CvRewriteService service;

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
        service = new CvRewriteService(builder, new ObjectMapper(),
                new ByteArrayResource("Return JSON.".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayResource("{}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void returnsValidRewriteOnFirstTry() {
        CvRewriteResult good = rewrite();
        when(call.entity(CvRewriteResult.class)).thenReturn(good);

        assertThat(service.rewrite("cv", "job", evaluation(), approved(), null)).isEqualTo(good);
    }

    @Test
    void retriesInvalidOutputThenSucceeds() {
        when(call.entity(CvRewriteResult.class))
                .thenReturn(new CvRewriteResult(null, List.of()))
                .thenReturn(rewrite());

        assertThat(service.rewrite("cv", "job", evaluation(), approved(), null).document().fullName())
                .isEqualTo("Jan Kowalski");
        verify(call, times(2)).entity(CvRewriteResult.class);
    }

    @Test
    void failsAfterMaxAttempts() {
        when(call.entity(eq(CvRewriteResult.class))).thenReturn(new CvRewriteResult(null, List.of()));

        assertThatThrownBy(() -> service.rewrite("cv", "job", evaluation(), approved(), null))
                .isInstanceOf(LlmInvalidOutputException.class);
        verify(call, times(3)).entity(CvRewriteResult.class);
    }

    @Test
    void validationRejectsEmptyDocuments() {
        assertThatThrownBy(() -> service.validate(null)).isInstanceOf(LlmInvalidOutputException.class);
        assertThatThrownBy(() -> service.validate(new CvRewriteResult(null, List.of())))
                .isInstanceOf(LlmInvalidOutputException.class);
        CvDocument empty = new CvDocument("  ", null, null, null, null, null, null, List.of(), List.of(), List.of(),
                List.of(), List.of());
        assertThatThrownBy(() -> service.validate(new CvRewriteResult(empty, List.of())))
                .isInstanceOf(LlmInvalidOutputException.class);
    }

    private static Evaluation evaluation() {
        return new Evaluation("Acme", "Senior Java Developer", 58, List.of("Java"), List.of("Kubernetes"),
                List.of(new Suggestion("Skills", "Missing K8s", "Group container skills", "Docker",
                        "Docker, Kubernetes (basics)", Priority.MEDIUM)),
                "Solid match.", "A backend role using Java and Kubernetes.");
    }

    private static List<Suggestion> approved() {
        return evaluation().suggestions();
    }

    private static CvRewriteResult rewrite() {
        CvDocument document = new CvDocument("Jan Kowalski", "Java Developer", "jan@example.com", null, null,
                List.of(), "Senior Java dev",
                List.of(new ExperienceEntry("Backend Developer", "Acme", "2020-2025", List.of("Built services"))),
                List.of(), List.of("Java", "Spring"), List.of(), List.of());
        return new CvRewriteResult(document,
                List.of(new CvChange("Summary", "old", "new", "Stronger opening")));
    }
}