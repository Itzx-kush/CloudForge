package com.cloudforge.backend.repository;

import com.cloudforge.backend.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

/**
 * Executes a real JDBC connectivity probe against the configured DataSource.
 */
@Repository
public class DatabaseProbeRepository {

    private static final Logger log = LoggerFactory.getLogger(DatabaseProbeRepository.class);
    private static final String VALIDATION_QUERY = "SELECT 1";

    private final DataSource dataSource;
    private final AppProperties appProperties;

    public DatabaseProbeRepository(DataSource dataSource, AppProperties appProperties) {
        this.dataSource = dataSource;
        this.appProperties = appProperties;
    }

    public DatabaseProbeResult probe() {
        long startedAtNanos = System.nanoTime();
        int timeoutSeconds = appProperties.getDatabaseCheckTimeoutSeconds();

        try (Connection connection = dataSource.getConnection()) {
            if (!connection.isValid(timeoutSeconds)) {
                long elapsedMs = elapsedMillis(startedAtNanos);
                log.warn("Database connection failed JDBC validity check after {}ms", elapsedMs);
                return new DatabaseProbeResult(
                        false,
                        "Unknown",
                        elapsedMs,
                        "Database connection failed validation check"
                );
            }

            try (PreparedStatement statement = connection.prepareStatement(VALIDATION_QUERY)) {
                statement.setQueryTimeout(timeoutSeconds);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next() || rs.getInt(1) != 1) {
                        long elapsedMs = elapsedMillis(startedAtNanos);
                        log.warn("Database probe query returned unexpected result after {}ms", elapsedMs);
                        return new DatabaseProbeResult(
                                false,
                                resolveEngine(connection),
                                elapsedMs,
                                "Database probe query returned an unexpected result"
                        );
                    }
                }
            }

            String engine = resolveEngine(connection);
            long elapsedMs = elapsedMillis(startedAtNanos);
            log.debug("Database connectivity probe succeeded ({}) in {}ms", engine, elapsedMs);

            return new DatabaseProbeResult(
                    true,
                    engine,
                    elapsedMs,
                    "Database connection verified"
            );
        } catch (SQLException ex) {
            long elapsedMs = elapsedMillis(startedAtNanos);
            log.warn(
                    "Database connectivity probe failed after {}ms (exception={}, SQLState={}, errorCode={})",
                    elapsedMs,
                    ex.getClass().getSimpleName(),
                    ex.getSQLState(),
                    ex.getErrorCode()
            );
            return new DatabaseProbeResult(
                    false,
                    "PostgreSQL",
                    elapsedMs,
                    "Database connectivity check failed"
            );
        } catch (RuntimeException ex) {
            long elapsedMs = elapsedMillis(startedAtNanos);
            log.warn(
                    "Unexpected error during database connectivity probe after {}ms (exception={})",
                    elapsedMs,
                    ex.getClass().getSimpleName()
            );
            return new DatabaseProbeResult(
                    false,
                    "PostgreSQL",
                    elapsedMs,
                    "Database connectivity check failed"
            );
        }
    }

    private String resolveEngine(Connection connection) {
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            String product = metaData.getDatabaseProductName();
            String version = metaData.getDatabaseProductVersion();
            if (product != null && !product.isBlank()) {
                if (version != null && !version.isBlank()) {
                    String shortVersion = version.split("\\s+")[0];
                    return product + " " + shortVersion;
                }
                return product;
            }
        } catch (SQLException ex) {
            log.debug("Could not inspect JDBC database metadata: {}", ex.getMessage());
        }
        return "Database";
    }

    private long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }
}
