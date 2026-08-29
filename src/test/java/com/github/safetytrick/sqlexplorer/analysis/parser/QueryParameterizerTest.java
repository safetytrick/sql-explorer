package com.github.safetytrick.sqlexplorer.analysis.parser;

import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cases taken from a real MySQL 8.4.4 — statements run against the
 * explorer-mysql container and read back as SQL_TEXT/DIGEST_TEXT pairs from
 * performance_schema.events_statements_history. These are measurements, not
 * recollections of the documentation.
 *
 * <p>The assertion is on the <em>digest-relevant</em> form rather than on
 * MySQL's exact rendering: both sides reach the hash through jsqlparser and
 * {@link IdentifierCanonicalizer}, so backticks, keyword case and spacing are
 * already gone by then. What has to survive is the parameterization.
 */
class QueryParameterizerTest {

    private final QueryParameterizer parameterizer = new QueryParameterizer();

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        // statement                                              | expected, after parameterizing
        "select id from orders where total > 100                  | select id from orders where total > ?",
        "select id from orders where name = 'bob'                 | select id from orders where name = ?",
        "select id from orders where total > 1.5                  | select id from orders where total > ?",
        "select id from orders where created_on > '2026-01-01'    | select id from orders where created_on > ?",
        "select id from orders where total between 1 and 9        | select id from orders where total between ? and ?",
        "select id from orders where total > 100 and total < 200  | select id from orders where total > ? and total < ?",
        "select id from orders where name like '%bob%'            | select id from orders where name like ?",
        "select id from orders limit 10                           | select id from orders limit ?",
        "update orders set total = 7 where id = 1                 | update orders set total = ? where id = ?",
        "delete from orders where id = 1                          | delete from orders where id = ?",
    })
    void matchesMysqlsParameterization(String sql, String expected) throws JSQLParserException {
        assertThat(normalizeSpace(parameterizer.parameterize(sql)))
            .isEqualToIgnoringCase(normalizeSpace(expected));
    }

    /**
     * MySQL keeps the sign as its own token: {@code > -5} digests to
     * {@code > - ?}, not {@code > ?}. Measured.
     */
    @Test
    void keepsTheSignOfANegativeLiteral() throws JSQLParserException {
        assertThat(normalizeSpace(parameterizer.parameterize("select id from orders where total > -5")))
            .isEqualToIgnoringCase("select id from orders where total > -?");
    }

    /**
     * {@code IS NULL} is syntax and MySQL leaves it alone; a bare {@code NULL}
     * used as a value is parameterized. Both measured.
     */
    @Test
    void leavesIsNullAloneButParameterizesNullAsAValue() throws JSQLParserException {
        assertThat(normalizeSpace(parameterizer.parameterize("select id from orders where total is null")))
            .containsIgnoringCase("is null");
        assertThat(normalizeSpace(parameterizer.parameterize("select id from orders where total = null")))
            .containsIgnoringCase("= ?");
    }

    /** Parameterizing an already-parameterized statement changes nothing. */
    @Test
    void isIdempotent() throws JSQLParserException {
        String once = parameterizer.parameterize("select id from orders where total > 100 and name = 'bob'");
        assertThat(normalizeSpace(parameterizer.parameterize(once))).isEqualTo(normalizeSpace(once));
    }

    /** Identifiers are not values and must survive untouched. */
    @Test
    void doesNotTouchIdentifiers() throws JSQLParserException {
        String out = parameterizer.parameterize("select `id`, `total` from `orders` where `member_id` = 42");

        assertThat(out).contains("id").contains("total").contains("orders").contains("member_id");
        assertThat(out).doesNotContain("42");
    }

    private static String normalizeSpace(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
