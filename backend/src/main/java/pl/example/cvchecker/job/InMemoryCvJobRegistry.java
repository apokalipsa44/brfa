package pl.example.cvchecker.job;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Job storage backed by a {@link ConcurrentHashMap}, with the recent history persisted through
 * {@link JobFileStore} so the queue survives a restart (docs/API.md §8). Callers only see
 * {@link CvJobRegistry}; swapping this for a database later still needs no caller changes (plan §6.4).
 */
@Component
public class InMemoryCvJobRegistry implements CvJobRegistry {

    private final Map<String, CvJob> jobs = new ConcurrentHashMap<>();
    private final JobFileStore store;

    public InMemoryCvJobRegistry(JobFileStore store) {
        this.store = store;
        restoreSaved();
    }

    /** Re-registers the persisted jobs so they are visible in the list after a restart. */
    private void restoreSaved() {
        for (StoredJob stored : store.load()) {
            CvJob job = CvJob.restored(stored);
            job.setChangeListener(this::persistAll);
            jobs.put(job.id(), job);
        }
        if (!jobs.isEmpty()) {
            persistAll(); // normalizes interrupted jobs to FAILED so the file reflects reality
        }
    }

    @Override
    public synchronized void create(CvJob job) {
        if (jobs.putIfAbsent(job.id(), job) != null) {
            throw new IllegalStateException("job already exists: " + job.id());
        }
        job.setChangeListener(this::persistAll);
        persistAll();
    }

    @Override
    public Optional<CvJob> find(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    @Override
    public synchronized boolean remove(String jobId) {
        boolean removed = jobs.remove(jobId) != null;
        if (removed) persistAll();
        return removed;
    }

    @Override
    public Collection<CvJob> findAll() {
        return jobs.values();
    }

    /** Persists the newest jobs; called on create, remove and every stage change. */
    private synchronized void persistAll() {
        List<StoredJob> snapshot = jobs.values().stream().map(CvJob::toStoredJob).toList();
        store.save(snapshot);
    }
}
