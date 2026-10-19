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

/** The standalone CV builder LLM step (docs/API.md §10). */
class CvBuildServiceTest {

    private ChatClient.CallResponseSpec call;
    private ChatClient.ChatClientRequestSpec request;
    private CvBuildService service;

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
        service = new CvBuildService(builder, new ObjectMapper(),
                new LlmStructuredCaller(new ObjectMapper()),
                new ByteArrayResource("Return JSON.".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayResource("{}".getBytes(StandardCharsets.UTF_8)),
                "none", 4096, java.time.Duration.ofMinutes(10));
    }

    @Test
    void returnsValidBuildOnFirstTry() {
        CvRewriteResult good = build();
        when(call.entity(CvRewriteResult.class)).thenReturn(good);

        assertThat(service.build("cv", List.of("Kubernetes"), null, null)).isEqualTo(good);
    }

    @Test
    void sendsTheCvPrefixAndTheSelectedSkillsBlock() {
        when(call.entity(CvRewriteResult.class)).thenReturn(build());

        service.build("my cv", List.of("Kubernetes", "GraphQL"), null, null);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(request).user(captor.capture());
        String prompt = captor.getValue();
        assertThat(prompt).startsWith("<cv>\nmy cv\n</cv>\n");
        assertThat(prompt).contains("<skills-to-add>\n[\"Kubernetes\",\"GraphQL\"]\n</skills-to-add>");
        assertThat(prompt).doesNotContain("<job>");
    }

    @Test
    void retriesInvalidOutputThenSucceeds() {
        when(call.entity(CvRewriteResult.class))
                .thenReturn(new CvRewriteResult(null, List.of()))
                .thenReturn(build());

        assertThat(service.build("cv", List.of("Kubernetes"), null, null).document().fullName())
                .isEqualTo("Jan Kowalski");
        verify(call, times(2)).entity(CvRewriteResult.class);
    }

    @Test
    void failsAfterMaxAttempts() {
        when(call.entity(eq(CvRewriteResult.class))).thenReturn(new CvRewriteResult(null, List.of()));

        assertThatThrownBy(() -> service.build("cv", List.of("Kubernetes"), null, null))
                .isInstanceOf(LlmInvalidOutputException.class);
        verify(call, times(3)).entity(CvRewriteResult.class);
    }

    private static CvRewriteResult build() {
        CvDocument document = new CvDocument("Jan Kowalski", "Java Developer", "jan@example.com", null, null,
                List.of(), "Senior Java dev",
                List.of(new ExperienceEntry("Backend Developer", "Acme", "2020-2025", List.of("Built services"))),
                List.of(), List.of("Backend: Java, Kubernetes"), List.of(), List.of());
        return new CvRewriteResult(document, List.of(new CvChange("Skills", "", "Kubernetes", "User selected")));
    }
}
