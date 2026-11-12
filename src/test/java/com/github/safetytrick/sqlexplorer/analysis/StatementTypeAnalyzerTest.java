package com.github.safetytrick.sqlexplorer.analysis;

import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A statement that parses is typed by what the parser read (sqlx-a9h). */
class StatementTypeAnalyzerTest {

    private static String typeOf(String sql) throws JSQLParserException {
        SqlProfile.Builder builder = SqlProfile.newBuilder()
            .setQuery(sql)
            .setId("id")
            .setType(StatementTypes.fromText(sql))
            .setRuntimeMetrics(null)
            .setQueryActivityStatus(QueryActivityStatus.UNKNOWN);
        new SqlAnalysisService(new StatementTypeAnalyzer()).analyzeSql(sql, builder);
        return builder.getType();
    }

    @Test
    void aCteOpeningAnUpdateIsAnUpdate() throws JSQLParserException {
        assertThat(typeOf("""
            WITH stale AS (SELECT id FROM account WHERE status = ?)
            UPDATE account a JOIN stale s ON s.id = a.id SET a.status = ?""")).isEqualTo("UPDATE");
    }

    @Test
    void aCteOpeningADeleteIsADelete() throws JSQLParserException {
        assertThat(typeOf("""
            WITH stale AS (SELECT id FROM site WHERE account_id = ?)
            DELETE FROM site WHERE id IN (SELECT id FROM stale)""")).isEqualTo("DELETE");
    }

    @Test
    void aCteOrParenthesisedQueryFeedingASelectIsASelect() throws JSQLParserException {
        assertThat(typeOf("WITH a AS (SELECT id FROM account) SELECT id FROM a")).isEqualTo("SELECT");
        assertThat(typeOf("(SELECT id FROM account) UNION (SELECT id FROM site)")).isEqualTo("SELECT");
    }

    @Test
    void anInsertKeepsTheKeywordItWasWrittenWith() throws JSQLParserException {
        assertThat(typeOf("INSERT INTO account (id) WITH a AS (SELECT ? AS id) SELECT id FROM a"))
            .isEqualTo("INSERT");
        assertThat(typeOf("REPLACE INTO account (id, status) VALUES (?, ?)")).isEqualTo("REPLACE");
    }

    @Test
    void anythingElseKeepsItsTextType() throws JSQLParserException {
        assertThat(typeOf("SET autocommit = 1")).isEqualTo("SET");
    }
}
