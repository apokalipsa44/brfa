package pl.example.cvchecker.gaps;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SQLite-backed skill-gap store (docs/API.md §9), reusing the single-connection datasource that also
 * persists analyses. Every curated gap is one row; the table is created on startup.
 */
@Component
@ConditionalOnProperty(name = "cvchecker.persistence", havingValue = "sqlite", matchIfMissing = true)
public class SqliteGapRepository implements GapRepository {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS skill_gaps (
                id         TEXT PRIMARY KEY,
                gap_key    TEXT NOT NULL,
                text       TEXT NOT NULL,
                kind       TEXT NOT NULL,
                status     TEXT NOT NULL,
                note       TEXT,
                manual     INTEGER NOT NULL,
                dismissed  INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """;

    private static final String UPSERT = """
            INSERT INTO skill_gaps (id, gap_key, text, kind, status, note, manual, dismissed, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                gap_key    = excluded.gap_key,
                text       = excluded.text,
                kind       = excluded.kind,
                status     = excluded.status,
                note       = excluded.note,
                manual     = excluded.manual,
                dismissed  = excluded.dismissed,
                created_at = excluded.created_at,
                updated_at = excluded.updated_at
            """;

    private final JdbcTemplate jdbc;

    public SqliteGapRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute(CREATE_TABLE);
    }

    @Override
    public List<SkillGap> findAll() {
        return jdbc.query("SELECT * FROM skill_gaps", (rs, rowNum) -> new SkillGap(
                rs.getString("id"),
                rs.getString("gap_key"),
                rs.getString("text"),
                GapKind.valueOf(rs.getString("kind")),
                GapStatus.valueOf(rs.getString("status")),
                rs.getString("note"),
                rs.getInt("manual") != 0,
                rs.getInt("dismissed") != 0,
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))));
    }

    @Override
    public Optional<SkillGap> find(String id) {
        return findAll().stream().filter(g -> g.id().equals(id)).findFirst();
    }

    @Override
    public void save(SkillGap gap) {
        jdbc.update(UPSERT, gap.id(), gap.key(), gap.text(), gap.kind().name(), gap.status().name(),
                gap.note(), gap.manual() ? 1 : 0, gap.dismissed() ? 1 : 0,
                gap.createdAt().toString(), gap.updatedAt().toString());
    }

    @Override
    public boolean delete(String id) {
        return jdbc.update("DELETE FROM skill_gaps WHERE id = ?", id) > 0;
    }
}
