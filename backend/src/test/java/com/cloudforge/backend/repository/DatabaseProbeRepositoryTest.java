package com.cloudforge.backend.repository;

import com.cloudforge.backend.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseProbeRepositoryTest {

    @Test
    void probeSucceedsAgainstLiveJdbcDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:probe_repo_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        dataSource.setDriverClassName("org.h2.Driver");

        DatabaseProbeRepository repository = new DatabaseProbeRepository(dataSource, new AppProperties());
        DatabaseProbeResult result = repository.probe();

        assertThat(result.connected()).isTrue();
        assertThat(result.engine()).contains("H2");
        assertThat(result.responseTimeMs()).isGreaterThanOrEqualTo(0L);
        assertThat(result.message()).isEqualTo("Database connection verified");
    }

    @Test
    void probeHandlesSqlExceptionGracefullyWithoutExposingCredentials() throws SQLException {
        DataSource failingDataSource = mock(DataSource.class);
        when(failingDataSource.getConnection())
                .thenThrow(new SQLException("Connection refused for secret_password", "08001"));

        DatabaseProbeRepository repository = new DatabaseProbeRepository(failingDataSource, new AppProperties());
        DatabaseProbeResult result = repository.probe();

        assertThat(result.connected()).isFalse();
        assertThat(result.message()).isEqualTo("Database connectivity check failed");
        assertThat(result.message()).doesNotContain("secret_password");
    }
}
