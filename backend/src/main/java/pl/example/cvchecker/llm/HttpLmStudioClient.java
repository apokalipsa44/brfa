package pl.example.cvchecker.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * HTTP implementation of {@link LmStudioClient} against LM Studio's native v1 REST API
 * ({@code cvchecker.lmstudio.base-url}) plus the OpenAI-compatible chat endpoint
 * ({@code spring.ai.openai.base-url}) for the readiness probe.
 */
@Component
public class HttpLmStudioClient implements LmStudioClient {

    private static final Logger log = LoggerFactory.getLogger(HttpLmStudioClient.class);

    private final RestTemplate listRestTemplate;
    private final RestTemplate loadRestTemplate;
    private final RestTemplate probeRestTemplate;
    private final String openAiBaseUrl;
    private final String nativeBaseUrl;

    /** Instance id of the last model this app loaded successfully — a fallback if the loaded list is unreadable. */
    private volatile String loadedInstance;

    public HttpLmStudioClient(@Value("${spring.ai.openai.base-url}") String openAiBaseUrl,
            @Value("${cvchecker.lmstudio.base-url}") String nativeBaseUrl,
            @Value("${cvchecker.lmstudio.load-timeout:120s}") Duration loadTimeout,
            @Value("${cvchecker.lmstudio.probe-timeout:10s}") Duration probeTimeout) {
        this.openAiBaseUrl = openAiBaseUrl;
        this.nativeBaseUrl = nativeBaseUrl;
        var quick = new SimpleClientHttpRequestFactory();
        quick.setConnectTimeout(Duration.ofSeconds(3));
        quick.setReadTimeout(Duration.ofSeconds(5));
        this.listRestTemplate = new RestTemplate(quick);
        var probe = new SimpleClientHttpRequestFactory();
        probe.setConnectTimeout(Duration.ofSeconds(3));
        probe.setReadTimeout(probeTimeout);
        this.probeRestTemplate = new RestTemplate(probe);
        // Loading a large model into VRAM can take tens of seconds; a short read timeout would
        // misreport a slow load as "unreachable" (docs/API.md §3).
        var slow = new SimpleClientHttpRequestFactory();
        slow.setConnectTimeout(Duration.ofSeconds(3));
        slow.setReadTimeout(loadTimeout);
        this.loadRestTemplate = new RestTemplate(slow);
    }

    @Override
    public LmStudioModelList listModels() {
        try {
            NativeModelList response = listRestTemplate
                    .getForObject(nativeBaseUrl + "/api/v1/models", NativeModelList.class);
            if (response == null || response.models() == null) {
                return new LmStudioModelList(false, List.of());
            }
            List<ModelInfo> models = response.models().stream()
                    .filter(m -> "llm".equalsIgnoreCase(m.type())
                            && m.key() != null && !m.key().isBlank())
                    .map(m -> new ModelInfo(m.key(),
                            m.loadedInstances() != null && !m.loadedInstances().isEmpty()))
                    .toList();
            return new LmStudioModelList(true, models);
        } catch (Exception e) {
            log.warn("Could not read models from LM Studio at {}: {}", nativeBaseUrl, e.getMessage());
            return new LmStudioModelList(false, List.of());
        }
    }

    @Override
    public void unloadAllAndLoad(String modelId) {
        unloadBeforeSwitching();
        try {
            LoadResult response = loadRestTemplate.postForObject(
                    nativeBaseUrl + "/api/v1/models/load", Map.of("model", modelId), LoadResult.class);
            // LM Studio returns the instance_id on load; the model key is the safest fallback.
            String instanceId = response == null || response.instanceId() == null || response.instanceId().isBlank()
                    ? modelId
                    : response.instanceId();
            this.loadedInstance = instanceId;
            log.info("Switched active model to {}", modelId);
        } catch (Exception e) {
            log.warn("Could not load model {}: {}", modelId, e.getMessage());
        }
    }

    @Override
    public boolean probe(String modelId) {
        try {
            probeRestTemplate.postForEntity(openAiBaseUrl + "/chat/completions",
                    Map.of("model", modelId,
                            "messages", List.of(Map.of("role", "user", "content", "ping")),
                            "max_tokens", 1),
                    String.class);
            return true;
        } catch (Exception e) {
            log.debug("Model probe failed for {}: {}", modelId, e.getMessage());
            return false;
        }
    }

    /**
     * Frees LM Studio's memory before a model switch: unloads the last instance this app loaded
     * plus every currently loaded LLM instance reported by GET /api/v1/models. Best-effort — a
     * failure (e.g. an instance that LM Studio refuses to unload) is logged and the load proceeds.
     */
    private void unloadBeforeSwitching() {
        List<String> instanceIds = new ArrayList<>();
        String tracked = this.loadedInstance;
        if (tracked != null && !tracked.isBlank()) {
            instanceIds.add(tracked);
        }
        try {
            NativeModelList list = listRestTemplate
                    .getForObject(nativeBaseUrl + "/api/v1/models", NativeModelList.class);
            if (list != null) {
                for (LmModel model : list.models()) {
                    if (model.loadedInstances() == null) {
                        continue;
                    }
                    for (Instance instance : model.loadedInstances()) {
                        if (instance.id() != null && !instance.id().isBlank()) {
                            instanceIds.add(instance.id());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not read loaded instances from LM Studio: {}", e.getMessage());
        }
        instanceIds.stream().distinct().forEach(this::unloadBestEffort);
    }

    /** Sends a single unload call; failures are logged, never fatal (docs/API.md §3). */
    private void unloadBestEffort(String instanceId) {
        try {
            loadRestTemplate.postForEntity(nativeBaseUrl + "/api/v1/models/unload",
                    Map.of("instance_id", instanceId), String.class);
            log.info("Unloaded model instance {} before switching", instanceId);
        } catch (Exception e) {
            log.warn("Could not unload model instance {}: {}", instanceId, e.getMessage());
        }
    }

    /** Native GET /api/v1/models envelope (docs/API.md §3). */
    private record NativeModelList(List<LmModel> models) {
    }

    private record LmModel(String key, String type,
            @JsonProperty("loaded_instances") List<Instance> loadedInstances) {
    }

    private record Instance(@JsonProperty("id") String id) {
    }

    /** Native POST /api/v1/models/load response: {@code {"instance_id": "…", "status": "loaded", …}}. */
    private record LoadResult(@JsonProperty("instance_id") String instanceId) {
    }
}