package com.github.safetytrick.sqlexplorer.environment;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

class AwsWrapperDriverTest {

    @Test
    void awsWrapperUrlsResolveToTheWrapperDriver() throws SQLException {
        Driver driver = DriverManager.getDriver(
            "jdbc:aws-wrapper:mysql://example.us-east-1.rds.amazonaws.com:3306/"
                + "?wrapperPlugins=iam&iamRegion=us-east-1&awsProfile=example&sslMode=REQUIRED");

        assertThat(driver.getClass().getName()).isEqualTo("software.amazon.jdbc.Driver");
    }
}
