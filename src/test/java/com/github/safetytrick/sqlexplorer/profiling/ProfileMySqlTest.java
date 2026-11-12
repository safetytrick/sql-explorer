package com.github.safetytrick.sqlexplorer.profiling;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.github.safetytrick.sqlexplorer.environment.ConnectionInfo;
import com.github.safetytrick.sqlexplorer.environment.DatabaseServer;
import com.github.safetytrick.sqlexplorer.environment.SamplingTarget;
import com.github.safetytrick.sqlexplorer.environment.ServerRole;
import com.github.safetytrick.sqlexplorer.profiling.ProfileResult.CharacterCount;
import com.github.safetytrick.sqlexplorer.profiling.ProfileResult.ColumnResult;
import com.github.safetytrick.sqlexplorer.profiling.ProfileResult.ServerResult;
import com.github.safetytrick.sqlexplorer.profiling.ProfileResult.SignatureResult;
import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Profiles run against a real MySQL (sqlx-n5j.3): the patterns as ICU reads
 * them, the windows, the characters listed, and the row keys. Neutral data,
 * seeded with each kind of corruption on known rows.
 */
@Testcontainers
class ProfileMySqlTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.4");

    private static DatabaseServer server;
    private static String db;

    private static final String NOTE = "CREATE TABLE app_note (id BIGINT NOT NULL, "
        + "title VARCHAR(100) CHARACTER SET latin1, body TEXT CHARACTER SET utf8mb4, "
        + "code CHAR(8) CHARACTER SET ascii, PRIMARY KEY (id))";
    private static final String SMALL = "CREATE TABLE app_small (id INT NOT NULL, "
        + "name VARCHAR(40) CHARACTER SET utf8mb4, PRIMARY KEY (id))";
    private static final String PAIR = "CREATE TABLE app_pair (a INT NOT NULL, b VARCHAR(8) NOT NULL, "
        + "label VARCHAR(40) CHARACTER SET utf8mb4, PRIMARY KEY (a, b))";

    @BeforeAll
    static void seed() throws SQLException {
        server = new DatabaseServer("app-replica",
            new ConnectionInfo(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()), List.of(),
            mock(SchemaRepositoryFactory.class), null, Set.of(ServerRole.REPLICA, ServerRole.SAMPLING));
        db = MYSQL.getDatabaseName();
        try (Connection connection = server.getDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(NOTE);
            statement.execute(SMALL);
            statement.execute(PAIR);
            statement.execute("SET SESSION cte_max_recursion_depth = 50000");
            statement.execute("INSERT INTO app_note (id, title, body, code) WITH RECURSIVE n (i) AS "
                + "(SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 30000) "
                + "SELECT i, 'plain title', 'plain body', 'AB12' FROM n");
            // UTF-8 bytes in the latin1 column, and genuine latin1, side by side: the mixed case
            statement.execute("UPDATE app_note SET title = X'636166C3A9' WHERE id = 1");
            statement.execute("UPDATE app_note SET title = X'636166E9' WHERE id IN (2, 29990)");
            statement.execute("INSERT INTO app_small (id, name) WITH RECURSIVE n (i) AS "
                + "(SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 50) SELECT i, 'name' FROM n");
            statement.execute("INSERT INTO app_pair VALUES (1, 'x', 'one'), (2, 'y', 'two')");
        }
        try (Connection connection = server.getDataSource().getConnection();
             PreparedStatement update = connection.prepareStatement("UPDATE app_note SET body = ? WHERE id = ?")) {
            Object[][] bodies = {
                {"cafÃ© au lait", 3}, {"donâ€™t", 4}, {"cafÃƒÂ©", 5}, {"caf�", 6},
                {"\u0093quoted\u0094", 7}, {"MÃ¼ller in the middle", 15_000}, {"cafÃ© at the end", 29_999},
                {"Jos? Garc?a", 8}};
            for (Object[] body : bodies) {
                update.setString(1, (String) body[0]);
                update.setInt(2, (Integer) body[1]);
                update.executeUpdate();
            }
        }
        try (Connection connection = server.getDataSource().getConnection();
             PreparedStatement update = connection.prepareStatement("UPDATE app_small SET name = ? WHERE id = 7")) {
            update.setString(1, "naïve");
            update.executeUpdate();
        }
    }

    @AfterAll
    static void close() {
        server.close();
    }

    private static ServerResult run(String create, String fullScan) throws Exception {
        TableStructure table = TableStructure.fromCreateTable(create, db).build();
        ProfilePlan plan = ProfilePlan.of(table, List.of(), fullScan);
        assertThat(plan.runnable()).as(plan.unavailable()).isTrue();
        return new ProfileRunner(plan, () -> false, () -> { })
            .run("cluster", new SamplingTarget("local", server, ProfilePlan.LIMITS), null);
    }

    private static Map<Signature, SignatureResult> signatures(ColumnResult column) {
        return column.signatures().stream().collect(Collectors.toMap(SignatureResult::signature, Function.identity()));
    }

    private static ColumnResult column(ServerResult result, String name) {
        return result.columns().stream().filter(c -> c.column().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void samplesTheWholeKeyRangeWithoutOverlap() throws Exception {
        ServerResult result = run(NOTE, null);

        assertThat(result.status()).as(result.reason()).isEqualTo("done");
        assertThat(result.complete()).isFalse();
        assertThat(result.windows()).hasSize(12).allMatch(w -> w.error() == null);
        assertThat(result.rowsRead()).isEqualTo(20_000);
        assertThat(result.windows().getFirst().from()).isEqualTo("1");
        assertThat(result.windows().getLast().to()).isEqualTo("30000");
        // windows are in key order and never overlap
        long previous = 0;
        for (ProfileResult.WindowResult window : result.windows()) {
            assertThat(Long.parseLong(window.from())).isGreaterThan(previous);
            previous = Long.parseLong(window.to());
        }
        assertThat(result.statements()).allMatch(s -> s.startsWith("SELECT"));
    }

    @Test
    void countsEachCorruptionWithTheRowsItIsInAndTheCharactersItWas() throws Exception {
        ServerResult result = run(NOTE, null);

        Map<Signature, SignatureResult> body = signatures(column(result, "body"));
        SignatureResult doubled = body.get(Signature.DOUBLE_ENCODED);
        // 3, 4, 5 (triple encoding also reads as double), 15000 in a middle window and 29999 in the tail
        assertThat(doubled.rows()).isEqualTo(5);
        assertThat(doubled.ids().getFirst().ids()).containsExactly("3", "4", "5", "15000", "29999");
        assertThat(doubled.characters()).extracting(CharacterCount::matched, CharacterCount::intended)
            .contains(org.assertj.core.groups.Tuple.tuple("Ã©", "é"), org.assertj.core.groups.Tuple.tuple("â€™", "’"));
        assertThat(body.get(Signature.TRIPLE_ENCODED).ids().getFirst().ids()).containsExactly("5");
        assertThat(body.get(Signature.TRIPLE_ENCODED).characters()).extracting(CharacterCount::intended)
            .containsExactly("é");
        assertThat(body.get(Signature.REPLACEMENT_CHARACTER).ids().getFirst().ids()).containsExactly("6");
        SignatureResult c1 = body.get(Signature.C1_CONTROL);
        assertThat(c1.ids().getFirst().ids()).containsExactly("7");
        assertThat(c1.characters()).extracting(CharacterCount::intended).containsExactly("“");
        assertThat(body.get(Signature.QUESTION_MARK).ids().getFirst().ids()).containsExactly("8");

        ColumnResult title = column(result, "title");
        Map<Signature, SignatureResult> latin1 = signatures(title);
        assertThat(latin1.get(Signature.UTF8_IN_LATIN1).ids().getFirst().ids()).containsExactly("1");
        assertThat(latin1.get(Signature.LATIN1_TEXT).ids().getFirst().ids()).containsExactly("2", "29990");
        assertThat(latin1.get(Signature.LATIN1_TEXT).characters()).extracting(CharacterCount::matched)
            .containsExactly("é");
        assertThat(title.verdict()).startsWith("mixed");

        ColumnResult code = column(result, "code");
        assertThat(code.signatures()).isEmpty();
        assertThat(code.nonAscii()).isZero();
        assertThat(code.verdict()).contains("not proof");
    }

    @Test
    void aSmallTableIsReadWholeAndSaysSo() throws Exception {
        ServerResult result = run(SMALL, null);

        assertThat(result.complete()).isTrue();
        assertThat(result.rowsRead()).isEqualTo(50);
        ColumnResult name = column(result, "name");
        assertThat(name.nonAscii()).isEqualTo(1);
        assertThat(name.verdict()).contains("in every row");
    }

    @Test
    void aCompositeKeyReadsOnlyTheEnds() throws Exception {
        ServerResult result = run(PAIR, null);

        assertThat(result.status()).as(result.reason()).isEqualTo("done");
        assertThat(result.complete()).isTrue();
        assertThat(result.rowsRead()).isEqualTo(2);
    }

    @Test
    void aFullScanReadsEveryRowOfOneColumn() throws Exception {
        ServerResult result = run(NOTE, "body");

        assertThat(result.complete()).isTrue();
        assertThat(result.rowsRead()).isEqualTo(30_000);
        assertThat(result.columns()).extracting(ColumnResult::column).containsExactly("body");
        assertThat(signatures(column(result, "body")).get(Signature.DOUBLE_ENCODED).rows()).isEqualTo(5);
    }

    @Test
    void theSessionIsReadOnly() throws Exception {
        TableStructure table = TableStructure.fromCreateTable(SMALL, db).build();
        try (SamplingTarget.SamplingSession session =
                 new SamplingTarget("local", server, ProfilePlan.LIMITS).openSession();
             Statement statement = session.connection().createStatement()) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                statement.execute("UPDATE " + table.name() + " SET name = 'x'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("read-only");
        }
    }
}
