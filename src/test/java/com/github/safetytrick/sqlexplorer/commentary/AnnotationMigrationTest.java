package com.github.safetytrick.sqlexplorer.commentary;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import liquibase.command.CommandScope;
import liquibase.command.core.UpdateCommandStep;
import liquibase.command.core.UpdateCountCommandStep;
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The explorer-database changes behind annotations, run as Liquibase runs them
 * against a real MySQL (sqlx-n5j.1, .13, .14).
 *
 * <p>The changelog is applied up to the changeSet that moves the `system` tag,
 * rows are written the way the old code wrote them, and then the rest runs —
 * which is the only way to show a data migration does what it says to data
 * that existed before it.
 */
@Testcontainers
class AnnotationMigrationTest {

    private static final String CHANGELOG = "db/migration/db.changelog-master.xml";
    private static final String FIRST_DATA_MIGRATION = "n5j-d-system-tag-to-annotation";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.4");

    @Test
    void theSystemSchemaTagBecomesAConfirmedAnnotationAndColumnsCanBeTagged() throws Exception {
        update(changeSetsBefore(FIRST_DATA_MIGRATION));

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            sql.execute("insert into tag_def (name, color) values ('system', '#f5a623')");
            sql.execute("insert into tags (name, type, schema_name, environment_name, author_id) values "
                + "('system', 'schema', 'mysql', 'regression', 1), "
                + "('system', 'schema', 'mysql', 'staging', 1), "
                + "('core', 'schema', 'app_main', 'regression', 1)");
            // a column tag, which this change makes possible
            sql.execute("insert into tags (name, type, schema_name, table_name, column_name, environment_name, author_id) "
                + "values ('pii', 'column', 'app_main', 'account', 'email', 'regression', 1), "
                + "('pii', 'column', 'app_main', 'account', 'phone', 'regression', 1)");
        }

        update(null);

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            assertThat(rows(sql, "select environment_name, target_type, schema_name, namespace, ann_key, value, "
                + "confidence, source_kind, author_id from annotations order by environment_name"))
                .containsExactly(
                    "regression|schema|mysql|schema-role|role|system|confirmed|person|1",
                    "staging|schema|mysql|schema-role|role|system|confirmed|person|1");
            assertThat(rows(sql, "select name, schema_name from tags where type = 'schema'"))
                .containsExactly("core|app_main");
            assertThat(rows(sql, "select name from tag_def")).isEmpty();
            assertThat(rows(sql, "select path from tags where type = 'column' order by path")).containsExactly(
                "/schema/app_main/table/account/column/email", "/schema/app_main/table/account/column/phone");

            // the column is part of the unique key: the same tag twice on one column is refused
            assertThatThrownBy(() -> sql.execute("insert into tags (name, type, schema_name, table_name, column_name, "
                + "environment_name, author_id) values ('pii', 'column', 'app_main', 'account', 'email', 'regression', 1)"))
                .isInstanceOf(SQLException.class).hasMessageContaining("Duplicate");
        }
    }

    /** How many changeSets precede the named one, in file order: the count to apply before it. */
    private static int changeSetsBefore(String id) throws IOException {
        String xml;
        try (InputStream in = AnnotationMigrationTest.class.getClassLoader().getResourceAsStream(CHANGELOG)) {
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher changeSet = Pattern.compile("<changeSet\\s+id=\"([^\"]+)\"").matcher(xml);
        int count = 0;
        while (changeSet.find()) {
            if (changeSet.group(1).equals(id)) {
                return count;
            }
            count++;
        }
        throw new IllegalStateException("no changeSet " + id);
    }

    /** Apply this many pending changeSets, or all of them for null. */
    private static void update(Integer count) throws Exception {
        CommandScope command = count == null
            ? new CommandScope(UpdateCommandStep.COMMAND_NAME)
                .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, CHANGELOG)
            : new CommandScope(UpdateCountCommandStep.COMMAND_NAME)
                .addArgumentValue(UpdateCountCommandStep.CHANGELOG_FILE_ARG, CHANGELOG)
                .addArgumentValue(UpdateCountCommandStep.COUNT_ARG, count);
        command.addArgumentValue(DbUrlConnectionArgumentsCommandStep.URL_ARG, MYSQL.getJdbcUrl())
            .addArgumentValue(DbUrlConnectionArgumentsCommandStep.USERNAME_ARG, MYSQL.getUsername())
            .addArgumentValue(DbUrlConnectionArgumentsCommandStep.PASSWORD_ARG, MYSQL.getPassword())
            .execute();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static List<String> rows(Statement sql, String query) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (ResultSet result = sql.executeQuery(query)) {
            int columns = result.getMetaData().getColumnCount();
            while (result.next()) {
                List<String> values = new ArrayList<>();
                for (int i = 1; i <= columns; i++) {
                    values.add(result.getString(i));
                }
                rows.add(String.join("|", values));
            }
        }
        return rows;
    }
}
