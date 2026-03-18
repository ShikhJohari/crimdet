package com.crimdet.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    private static final String DEFAULT_JDBC_URL = "jdbc:h2:./data/crimdet;AUTO_SERVER=TRUE";
    private static final String SCHEMA_RESOURCE = "/db/schema.sql";

    private static DatabaseConfig instance;

    private final HikariDataSource dataSource;
    private final Jdbi jdbi;

    private DatabaseConfig(String jdbcUrl) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);

        this.dataSource = new HikariDataSource(config);
        this.jdbi = Jdbi.create(dataSource);

        runSchema();
        log.info("Database initialized: {}", jdbcUrl);
    }

    public static synchronized DatabaseConfig getInstance() {
        if (instance == null) {
            instance = new DatabaseConfig(DEFAULT_JDBC_URL);
        }
        return instance;
    }

    public static DatabaseConfig create(String jdbcUrl) {
        return new DatabaseConfig(jdbcUrl);
    }

    public Jdbi getJdbi() {
        return jdbi;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("Database connection pool closed");
        }
    }

    private void runSchema() {
        try (InputStream is = getClass().getResourceAsStream(SCHEMA_RESOURCE)) {
            if (is == null) {
                throw new RuntimeException("Schema resource not found: " + SCHEMA_RESOURCE);
            }
            String sql = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));

            jdbi.useHandle(handle -> handle.createScript(sql).execute());
            log.info("Schema executed successfully");
        } catch (IOException e) {
            throw new RuntimeException("Failed to read schema", e);
        }
    }
}
