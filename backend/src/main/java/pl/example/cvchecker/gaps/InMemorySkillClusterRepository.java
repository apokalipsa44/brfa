package pl.example.cvchecker.gaps;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Volatile grouped-skill store (docs/API.md §9): selected only with
 * {@code cvchecker.persistence=memory} (tests); the default is the SQLite-backed
 * {@code SqliteSkillClusterRepository}.
 */
@Component
@ConditionalOnProperty(name = "cvchecker.persistence", havingValue = "memory")
public class InMemorySkillClusterRepository implements SkillClusterRepository {

    private volatile List<SkillCluster> clusters = List.of();

    @Override
    public List<SkillCluster> findAll() {
        return List.copyOf(clusters);
    }

    @Override
    public void replaceAll(List<SkillCluster> clusters) {
        this.clusters = List.copyOf(clusters);
    }
}
