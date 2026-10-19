package pl.example.cvchecker.gaps;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SQLite-backed grouped-skill store (docs/API.md §9), reusing the single-connection datasource that
 * also persists analyses and gaps. One row per cluster plus one row per member gap; the job replaces
 * the whole set on every run.
 */
@Component
@ConditionalOnProperty(name = "cvchecker.persistence", havingValue = "sqlite", matchIfMissing = true)
public class SqliteSkillClusterRepository implements SkillClusterRepository {

    private static final String CREATE_CLUSTERS = """
            CREATE TABLE IF NOT EXISTS skill_clusters (
                id         TEXT PRIMARY KEY,
                label      TEXT NOT NULL,
                importance INTEGER NOT NULL,
                rationale  TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """;

    private static final String CREATE_MEMBERS = """
            CREATE TABLE IF NOT EXISTS skill_cluster_members (
                cluster_id TEXT NOT NULL,
                gap_id     TEXT NOT NULL
            )
            """;

    private final JdbcTemplate jdbc;

    public SqliteSkillClusterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute(CREATE_CLUSTERS);
        jdbc.execute(CREATE_MEMBERS);
    }

    @Override
    public List<SkillCluster> findAll() {
        Map<String, List<String>> members = new LinkedHashMap<>();
        jdbc.query("SELECT cluster_id, gap_id FROM skill_cluster_members",
                rs -> { members.computeIfAbsent(rs.getString("cluster_id"), k -> new ArrayList<>())
                        .add(rs.getString("gap_id")); });
        return jdbc.query("SELECT * FROM skill_clusters", (rs, rowNum) -> new SkillCluster(
                rs.getString("id"),
                rs.getString("label"),
                rs.getInt("importance"),
                rs.getString("rationale"),
                List.copyOf(members.getOrDefault(rs.getString("id"), List.of())),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))));
    }

    @Override
    public void replaceAll(List<SkillCluster> clusters) {
        jdbc.update("DELETE FROM skill_cluster_members");
        jdbc.update("DELETE FROM skill_clusters");
        for (SkillCluster cluster : clusters) {
            jdbc.update("INSERT INTO skill_clusters (id, label, importance, rationale, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    cluster.id(), cluster.label(), cluster.importance(), cluster.rationale(),
                    cluster.createdAt().toString(), cluster.updatedAt().toString());
            for (String gapId : cluster.memberGapIds()) {
                jdbc.update("INSERT INTO skill_cluster_members (cluster_id, gap_id) VALUES (?, ?)",
                        cluster.id(), gapId);
            }
        }
    }
}
