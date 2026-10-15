package pl.example.cvchecker.analysis;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * SQLite-backed analysis registry (docs/API.md §8). The in-memory map stays the hot path (the whole
 * pipeline and API read through it); every change is write-through persisted as a JSON snapshot in
 * the {@code analyses} table, and the table is loaded back on startup.
 *
 * <p>A restart interrupts any non-terminal analysis (its temp files are gone): it is restored as
 * {@code FAILED} with {@code errorCode = "INTERRUPTED"} and {@code failedStage} set to the stage it
 * was in, so retry resumes there. {@code AWAITING_REVIEW} and terminal analyses are restored as-is.
 * The single-connection pool (application.yml) serialises writes. Lives in this package next to
 * {@link InMemoryAnalysisRegistry} so it can use the package-private change-listener seam.
 */
@Component
@ConditionalOnProperty(name = "cvchecker.persistence", havingValue = "sqlite", matchIfMissing = true)
public class SqliteAnalysisRegistry implements AnalysisRegistry {

    private static final Logger log = LoggerFactory.getLogger(SqliteAnalysisRegistry.class);

    public static final String INTERRUPTED = "INTERRUPTED";

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS analyses (
                id         TEXT PRIMARY KEY,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                stage      TEXT NOT NULL,
                applied_at TEXT,
                state      TEXT NOT NULL
            )
            """;

    private static final String UPSERT = """
            INSERT INTO analyses (id, created_at, updated_at, stage, applied_at, state)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                created_at = excluded.created_at,
                updated_at = excluded.updated_at,
                stage      = excluded.stage,
                applied_at = excluded.applied_at,
                state      = excluded.state
            """;

    private final Map<String, Analysis> analyses = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;

    public SqliteAnalysisRegistry(JdbcTemplate jdbc, ObjectMapper objectMapper,
            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.events = events;
        jdbc.execute(CREATE_TABLE);
        restore();
    }

    @Override
    public void create(Analysis analysis) {
        if (analyses.putIfAbsent(analysis.id(), analysis) != null) {
            throw new IllegalStateException("analysis already exists: " + analysis.id());
        }
        analysis.setChangeListener(() -> onChange(analysis));
        save(analysis);
        events.publishEvent(new AnalysisUpdatedEvent(analysis.summary())); // the initial QUEUED state
    }

    @Override
    public Optional<Analysis> find(String id) {
        return Optional.ofNullable(analyses.get(id));
    }

    @Override
    public boolean remove(String id) {
        if (analyses.remove(id) == null) {
            return false;
        }
        jdbc.update("DELETE FROM analyses WHERE id = ?", id);
        events.publishEvent(new AnalysisDeletedEvent(id));
        return true;
    }

    @Override
    public Collection<Analysis> findAll() {
        return analyses.values();
    }

    private void onChange(Analysis analysis) {
        save(analysis);
        events.publishEvent(new AnalysisUpdatedEvent(analysis.summary()));
    }

    private synchronized void save(Analysis analysis) {
        AnalysisState state = analysis.toState();
        jdbc.update(UPSERT, state.id(), state.createdAt().toString(), state.updatedAt().toString(),
                state.stage().name(), state.appliedAt() == null ? null : state.appliedAt().toString(),
                write(state));
    }

    private void restore() {
        List<Analysis> restored = jdbc.query("SELECT state FROM analyses",
                (rs, rowNum) -> Analysis.fromState(read(rs.getString("state"))));
        int interrupted = 0;
        for (Analysis analysis : restored) {
            boolean wasInterrupted = analysis.stage() != AnalysisStage.AWAITING_REVIEW
                    && !analysis.stage().isTerminal();
            if (wasInterrupted) {
                analysis.fail(INTERRUPTED, "The backend restarted while this analysis was running.",
                        analysis.stage());
                interrupted++;
            }
            analysis.setChangeListener(() -> onChange(analysis));
            analyses.put(analysis.id(), analysis);
            if (wasInterrupted) {
                save(analysis);
            }
        }
        if (!restored.isEmpty()) {
            log.info("Restored {} analyses from SQLite ({} interrupted by the restart)",
                    restored.size(), interrupted);
        }
    }

    private String write(AnalysisState state) {
        return objectMapper.writeValueAsString(state);
    }

    private AnalysisState read(String json) {
        return objectMapper.readValue(json, AnalysisState.class);
    }
}
