package pl.example.cvchecker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dedicated bounded executor for the analysis pipeline (plan §6.7). A local LLM processes one
 * analysis at a time, so {@code max-concurrent} defaults to 1 and excess analyses wait in a bounded
 * queue instead of hammering the model.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "pipelineExecutor")
    public ThreadPoolTaskExecutor pipelineExecutor(
            @Value("${cvchecker.analyses.max-concurrent:1}") int maxConcurrent) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(maxConcurrent);
        executor.setMaxPoolSize(maxConcurrent);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("pipeline-");
        executor.initialize();
        return executor;
    }
}
