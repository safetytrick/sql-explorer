package com.github.safetytrick.sqlexplorer.environment;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

/**
 * Servers that use RDS IAM authentication are configured with a
 * {@code jdbc:aws-wrapper:mysql://…?wrapperPlugins=iam} URL (see "RDS servers
 * with IAM authentication" in docs/running-sql-explorer.md). Nothing in the code names the wrapper —
 * both the metadata pool and the capture source find a driver through
 * {@link DriverManager} — so the only thing keeping those servers reachable is
 * the runtime dependency. This fails if it is dropped, rather than production
 * reads failing with "No suitable driver" (sqlx-otn).
 */
class AwsWrapperDriverTest {

    @Test
    void awsWrapperUrlsResolveToTheWrapperDriver() throws SQLException {
        Driver driver = DriverManager.getDriver(
            "jdbc:aws-wrapper:mysql://example.us-east-1.rds.amazonaws.com:3306/"
                + "?wrapperPlugins=iam&iamRegion=us-east-1&awsProfile=example&sslMode=REQUIRED");

        assertThat(driver.getClass().getName()).isEqualTo("software.amazon.jdbc.Driver");
    }
}
