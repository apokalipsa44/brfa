package pl.example.cvchecker.llm;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CvAnalysisServiceTest {

    private ChatClient chat;
    private ChatClient.CallResponseSpec call;
    private CvAnalysisService service;

    @BeforeEach
    void setUp() {
        chat = mock(ChatClient.class);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chat);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        call = mock(ChatClient.CallResponseSpec.class);
        when(chat.prompt()).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.call()).thenReturn(call);
        service = new CvAnalysisService(builder,
                new ByteArrayResource("Return JSON.".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void returnsValidAnalysisOnFirstTry() {
        CvAnalysis good = analysis(58);
        when(call.entity(CvAnalysis.class)).thenReturn(good);

        assertThat(service.analyze("cv", "job")).isEqualTo(good);
    }

    @Test
    void retriesInvalidOutputThenSucceeds() {
        when(call.entity(CvAnalysis.class)).thenReturn(analysis(150), analysis(61));

        assertThat(service.analyze("cv", "job").matchScore()).isEqualTo(61);
        verify(call, times(2)).entity(CvAnalysis.class);
    }

    @Test
    void failsAfterMaxAttempts() {
        when(call.entity(CvAnalysis.class)).thenReturn(analysis(150));

        assertThatThrownBy(() -> service.analyze("cv", "job")).isInstanceOf(LlmInvalidOutputException.class);
        verify(call, times(3)).entity(CvAnalysis.class);
    }

    @Test
    void retriesModelCallFailures() {
        when(call.entity(eq(CvAnalysis.class))).thenThrow(new RuntimeException("model loading")).thenReturn(analysis(70));

        assertThat(service.analyze("cv", "job").matchScore()).isEqualTo(70);
    }

    @Test
    void validationRejectsBadScoresAndEmptySections() {
        assertThatThrownBy(() -> service.validate(analysis(-1))).isInstanceOf(LlmInvalidOutputException.class);
        assertThatThrownBy(() -> service.validate(analysis(101))).isInstanceOf(LlmInvalidOutputException.class);
        assertThatThrownBy(() -> service.validate(null)).isInstanceOf(LlmInvalidOutputException.class);
        assertThatThrownBy(() -> service.validate(new CvAnalysis(50, List.of(), List.of(), List.of(), "  ")))
                .isInstanceOf(LlmInvalidOutputException.class);
    }

    private static CvAnalysis analysis(int score) {
        return new CvAnalysis(score, List.of("Java"), List.of("Kubernetes"),
                List.of(new Suggestion("Skills", "Missing K8s", "Add K8s course", Priority.MEDIUM)), "Solid match.");
    }
}
