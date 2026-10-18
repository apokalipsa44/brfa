package pl.example.cvchecker.gaps;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Volatile skill-gap store (docs/API.md §9): selected only with {@code cvchecker.persistence=memory}
 * (tests); the default is the SQLite-backed {@code SqliteGapRepository}.
 */
@Component
@ConditionalOnProperty(name = "cvchecker.persistence", havingValue = "memory")
public class InMemoryGapRepository implements GapRepository {

    private final Map<String, SkillGap> gaps = new ConcurrentHashMap<>();

    @Override
    public List<SkillGap> findAll() {
        return new ArrayList<>(gaps.values());
    }

    @Override
    public Optional<SkillGap> find(String id) {
        return Optional.ofNullable(gaps.get(id));
    }

    @Override
    public void save(SkillGap gap) {
        gaps.put(gap.id(), gap);
    }

    @Override
    public boolean delete(String id) {
        return gaps.remove(id) != null;
    }
}
