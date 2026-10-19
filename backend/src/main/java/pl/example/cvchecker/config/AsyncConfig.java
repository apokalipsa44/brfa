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

    /**
     * Dedicated single-thread executor for the background skill-regrouping job (docs/API.md §9). It is
     * separate from {@code pipelineExecutor} so an LLM regrouping run never delays or blocks an
     * analysis; the job is idempotent, so a queue of one is plenty.
     */
    @Bean(name = "skillGroupingExecutor")
    public ThreadPoolTaskExecutor skillGroupingExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("skill-grouping-");
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated single-thread executor for the standalone CV builder (docs/API.md §10). Separate from
     * {@code pipelineExecutor} so a build never delays or blocks an analysis; only one build runs at a
     * time (the service refuses a concurrent start), so a queue of one is plenty.
     */
    @Bean(name = "cvImprovementExecutor")
    public ThreadPoolTaskExecutor cvImprovementExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("cv-improvement-");
        executor.initialize();
        return executor;
    }
}
