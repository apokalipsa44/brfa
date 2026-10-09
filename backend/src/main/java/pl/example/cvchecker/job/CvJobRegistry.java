package pl.example.cvchecker.job;

import java.util.Optional;

/** Job storage. In-memory today, replaceable with a database without touching callers (plan §6.4). */
public interface CvJobRegistry {

    void create(CvJob job);

    Optional<CvJob> find(String jobId);

    boolean remove(String jobId);
}
