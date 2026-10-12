package pl.example.cvchecker.llm;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import pl.example.cvchecker.api.ApiException;
import pl.example.cvchecker.sse.ModelsUpdatedEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Readiness logic and selection semantics of the persistent model context (docs/API.md §3). */
class ModelContextServiceTest {

    private static final String BASE = "http://localhost:1234";

    private final LmStudioClient client = mock(LmStudioClient.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ModelContextService service = new ModelContextService(client, events, BASE);

    private static List<ModelInfo> models() {
        return List.of(new ModelInfo("qwen/iq3_s.gguf", true), new ModelInfo("gemma-4-e4b", false));
    }

    @Test
    void unreachableReportsReadyFalseWithReason() {
        when(client.listModels()).thenReturn(new LmStudioModelList(false, List.of()));

        ModelState state = service.state();

        assertThat(state.ready()).isFalse();
        assertThat(state.reason()).contains("LM Studio unreachable");
        assertThat(state.models()).isEmpty();
    }

    @Test
    void nothingSelectedReportsNoModelSelected() {
        when(client.listModels()).thenReturn(new LmStudioModelList(true, models()));

        ModelState state = service.state();

        assertThat(state.ready()).isFalse();
        assertThat(state.reason()).isEqualTo("No model selected");
        assertThat(state.activeModelId()).isNull();
    }

    @Test
    void selectedButNotLoadedReportsNotLoaded() throws Exception {
        when(client.listModels()).thenReturn(new LmStudioModelList(true, models()));
        // select qwen (unloaded): selection recorded, but the model has no loaded instance
        service.select("gemma-4-e4b");
        when(client.listModels()).thenReturn(new LmStudioModelList(true, models()));

        ModelState state = service.state();

        assertThat(state.ready()).isFalse();
        assertThat(state.reason()).isEqualTo("The selected model is not loaded");
    }

    @Test
    void loadedButSilentReportsDidNotAnswer() throws Exception {
        when(client.listModels()).thenReturn(new LmStudioModelList(true, models()));
        service.select("qwen/iq3_s.gguf");
        when(client.probe("qwen/iq3_s.gguf")).thenReturn(false);

        ModelState state = service.state();

        assertThat(state.ready()).isFalse();
        assertThat(state.reason()).isEqualTo("LM Studio did not answer");
    }

    @Test
    void readyWhenLoadedAndAnswering() throws Exception {
        when(client.listModels()).thenReturn(new LmStudioModelList(true, models()));
        service.select("qwen/iq3_s.gguf");
        when(client.probe("qwen/iq3_s.gguf")).thenReturn(true);

        ModelState state = service.state();

        assertThat(state.ready()).isTrue();
        assertThat(state.reason()).isNull();
        assertThat(state.activeModelId()).isEqualTo("qwen/iq3_s.gguf");
    }

    @Test
    void selectRecordsAndLoadsAndProbes() throws Exception {
        when(client.listModels()).thenReturn(new LmStudioModelList(true, models()));

        ModelState state = service.select("qwen/iq3_s.gguf");

        verify(client).unloadAllAndLoad("qwen/iq3_s.gguf");
        verify(events).publishEvent(new ModelsUpdatedEvent("qwen/iq3_s.gguf"));
        assertThat(state.activeModelId()).isEqualTo("qwen/iq3_s.gguf");
    }

    @Test
    void selectRecordsEvenWhenUnreachable() throws Exception {
        when(client.listModels()).thenReturn(new LmStudioModelList(false, List.of()));

        ModelState state = service.select("qwen/iq3_s.gguf");

        verify(client, never()).unloadAllAndLoad("qwen/iq3_s.gguf");
        assertThat(state.activeModelId()).isEqualTo("qwen/iq3_s.gguf");
        assertThat(state.ready()).isFalse();
        assertThat(state.reason()).contains("LM Studio unreachable");
    }

    @Test
    void blankSelectionThrowsModelBlank() {
        assertThatThrownBy(() -> service.select("  ")).isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.status()).isEqualTo(400);
                    assertThat(ex.code()).isEqualTo("MODEL_BLANK");
                });
    }

    @Test
    void unknownSelectionThrowsModelNotFound() {
        when(client.listModels()).thenReturn(new LmStudioModelList(true, models()));

        assertThatThrownBy(() -> service.select("bogus/model.gguf")).isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.status()).isEqualTo(400);
                    assertThat(ex.code()).isEqualTo("MODEL_NOT_FOUND");
                });
    }

    @Test
    void isReadyMirrorsState() throws Exception {
        when(client.listModels()).thenReturn(new LmStudioModelList(true, models()));
        service.select("qwen/iq3_s.gguf");
        when(client.probe("qwen/iq3_s.gguf")).thenReturn(true);

        assertThat(service.isReady()).isTrue();
    }
}