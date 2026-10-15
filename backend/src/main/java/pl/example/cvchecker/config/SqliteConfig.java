package pl.example.cvchecker.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SQLite {@link DataSource} for the analysis store (docs/API.md §8). SQLite does not create missing
 * parent directories, so the database file's directory is created here before Hikari opens the
 * connection. Selected for the default {@code cvchecker.persistence=sqlite}; the in-memory registry
 * used by tests does not need a datasource. Boot's own {@code DataSourceAutoConfiguration} backs off
 * because this bean is defined.
 */
@Configuration
@ConditionalOnProperty(name = "cvchecker.persistence", havingValue = "sqlite", matchIfMissing = true)
public class SqliteConfig {

    private static final String JDBC_PREFIX = "jdbc:sqlite:";

    @Bean
    public DataSource dataSource(@Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.hikari.maximum-pool-size:1}") int maximumPoolSize) {
        ensureParentDirectory(url);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        // SQLite has a single writer; one connection avoids SQLITE_BUSY (docs/API.md §8)
        config.setMaximumPoolSize(maximumPoolSize);
        return new HikariDataSource(config);
    }

    private static void ensureParentDirectory(String url) {
        if (!url.startsWith(JDBC_PREFIX)) return;
        String file = url.substring(JDBC_PREFIX.length());
        if (file.isBlank() || file.equals(":memory:") || file.startsWith("file:")) return;
        Path parent = Path.of(file).toAbsolutePath().getParent();
        if (parent == null) return;
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create the SQLite directory " + parent, e);
        }
    }
}
