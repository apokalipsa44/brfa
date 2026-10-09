package pl.example.cvchecker.job;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class InMemoryCvJobRegistry implements CvJobRegistry {

    private final Map<String, CvJob> jobs = new ConcurrentHashMap<>();

    @Override
    public void create(CvJob job) {
        if (jobs.putIfAbsent(job.id(), job) != null) {
            throw new IllegalStateException("job already exists: " + job.id());
        }
    }

    @Override
    public Optional<CvJob> find(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    @Override
    public boolean remove(String jobId) {
        return jobs.remove(jobId) != null;
    }

    @Override
    public Collection<CvJob> findAll() {
        return jobs.values();
    }
}
