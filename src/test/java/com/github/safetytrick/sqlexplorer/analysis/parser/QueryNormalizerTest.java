package com.github.safetytrick.sqlexplorer.analysis.parser;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class QueryNormalizerTest {

    private final QueryNormalizer queryNormalizer = new QueryNormalizer();

    @Test
    public void testNormalizeEquivalentHibernateQueries() throws JSQLParserException {
        String sqlOne = """
                SELECT
                    `this_` . `id` AS `id92_0_` ,
                    `this_` . `request_time` AS `request2_92_0_` ,
                    `this_` . `event_time` AS `event3_92_0_` ,
                    `this_` . `errors` AS `errors92_0_` ,
                    `this_` . `type` AS `type92_0_` ,
                    `this_` . `recipient_id` AS `recipient6_92_0_` ,
                    `this_` . `recipient_name` AS `recipient7_92_0_` ,
                    `this_` . `begin_processing` AS `begin8_92_0_` ,
                    `this_` . `meets_rule` AS `meets9_92_0_` ,
                    `this_` . `completed_on` AS `completed10_92_0_` ,
                    `this_` . `transaction_id` AS `transac11_92_0_` ,
                    `this_` . `pages_sent` AS `pages12_92_0_` ,
                    `this_` . `destination` AS `destina13_92_0_` ,
                    `this_` . `reason` AS `reason92_0_` ,
                    `this_` . `member_id` AS `member15_92_0_` ,
                    `this_` . `branch_id` AS `branch16_92_0_` ,
                    `this_` . `sender_id` AS `sender17_92_0_` ,
                    `this_` . `sent_to_branch_id` AS `sent18_92_0_` ,
                    `this_` . `member_session_id` AS `member19_92_0_`\s
                FROM
                    `org_alert` `this_`\s
                WHERE
                    `this_` . `branch_id` = ?\s
                    AND `this_` . `sent_to_branch_id` = ?\s
                    AND `this_` . `type` \
            """;

        String sqlTwo = """
                SELECT
                    `this_` . `id` AS `id87_0_` ,
                    `this_` . `request_time` AS `request2_87_0_` ,
                    `this_` . `event_time` AS `event3_87_0_` ,
                    `this_` . `errors` AS `errors87_0_` ,
                    `this_` . `type` AS `type87_0_` ,
                    `this_` . `recipient_id` AS `recipient6_87_0_` ,
                    `this_` . `recipient_name` AS `recipient7_87_0_` ,
                    `this_` . `begin_processing` AS `begin8_87_0_` ,
                    `this_` . `meets_rule` AS `meets9_87_0_` ,
                    `this_` . `completed_on` AS `completed10_87_0_` ,
                    `this_` . `transaction_id` AS `transac11_87_0_` ,
                    `this_` . `pages_sent` AS `pages12_87_0_` ,
                    `this_` . `destination` AS `destina13_87_0_` ,
                    `this_` . `reason` AS `reason87_0_` ,
                    `this_` . `member_id` AS `member15_87_0_` ,
                    `this_` . `branch_id` AS `branch16_87_0_` ,
                    `this_` . `sender_id` AS `sender17_87_0_` ,
                    `this_` . `sent_to_branch_id` AS `sent18_87_0_` ,
                    `this_` . `member_session_id` AS `member19_87_0_`\s
                FROM
                    `org_alert` `this_`\s
                WHERE
                    `this_` . `branch_id` = ?\s
                    AND `this_` . `sent_to_branch_id` = ?\s
                    AND `this_` . `type` \
            """;

        String normalizedOne = queryNormalizer.normalize(CCJSqlParserUtil.parse(sqlOne));
        String normalizedTwo = queryNormalizer.normalize(CCJSqlParserUtil.parse(sqlTwo));

        assertEquals(normalizedOne, normalizedTwo);
    }

    @Test
    public void collapsesRepeatedOrPredicates() throws JSQLParserException {
        assertNormalizesEqual(
            "SELECT `id` FROM `t` WHERE `a` LIKE ? OR `a` LIKE ? OR `a` LIKE ?",
            "SELECT `id` FROM `t` WHERE `a` LIKE ?");
    }

    @Test
    public void keepsDistinctPredicatesInOrder() throws JSQLParserException {
        assertNormalizesEqual(
            "SELECT `id` FROM `t` WHERE `a` = ? OR `b` = ? OR `a` = ? OR `b` = ?",
            "SELECT `id` FROM `t` WHERE `a` = ? OR `b` = ?");
    }

    @Test
    public void collapsesRepeatsInsideJoinOnConditions() throws JSQLParserException {
        assertNormalizesEqual(
            "SELECT `t`.`id` FROM `t` JOIN `u` ON `t`.`id` = `u`.`t_id` AND `t`.`id` = `u`.`t_id`",
            "SELECT `t`.`id` FROM `t` JOIN `u` ON `t`.`id` = `u`.`t_id`");
    }

    @Test
    public void collapsesRepeatedCaseArms() throws JSQLParserException {
        assertNormalizesEqual(
            "SELECT CASE WHEN `a` = ? THEN ? WHEN `a` = ? THEN ? ELSE ? END FROM `t`",
            "SELECT CASE WHEN `a` = ? THEN ? ELSE ? END FROM `t`");
    }

    @Test
    public void collapsesBatchedInsertRows() throws JSQLParserException {
        assertNormalizesEqual(
            "INSERT INTO `t` (`a`, `b`) VALUES (?, ?), (?, ?), (?, ?)",
            "INSERT INTO `t` (`a`, `b`) VALUES (?, ?)");
    }

    @Test
    public void doesNotCollapseFunctionArguments() throws JSQLParserException {
        String sql = "SELECT CONCAT(?, ?, ?) FROM `t`";
        String normalized = queryNormalizer.normalize(CCJSqlParserUtil.parse(sql));
        assertEquals(2, normalized.length() - normalized.replace(",", "").length());
    }

    @Test
    public void doesNotCollapseDistinctInsertRows() throws JSQLParserException {
        String sql = "INSERT INTO `t` (`a`) VALUES (1), (2)";
        String normalized = queryNormalizer.normalize(CCJSqlParserUtil.parse(sql));
        org.assertj.core.api.Assertions.assertThat(normalized).contains("1").contains("2");
    }

    @Test
    public void reportsRepeatSnippetsAndCounts() throws JSQLParserException {
        QueryNormalizer.NormalizedSql normalized = queryNormalizer.normalizeWithRepeats(CCJSqlParserUtil.parse(
            "SELECT `id` FROM `t` WHERE (`a` LIKE ? OR `a` LIKE ? OR `a` LIKE ?) AND `b` = ?"));

        org.assertj.core.api.Assertions.assertThat(normalized.repeats())
            .extracting(QueryNormalizer.Repeat::snippet, QueryNormalizer.Repeat::count)
            .containsExactly(org.assertj.core.api.Assertions.tuple("`a` LIKE ?", 3));
    }

    @Test
    public void reportsRepeatsPerConstruct() throws JSQLParserException {
        QueryNormalizer.NormalizedSql insert = queryNormalizer.normalizeWithRepeats(CCJSqlParserUtil.parse(
            "INSERT INTO `t` (`a`, `b`) VALUES (?, ?), (?, ?), (?, ?)"));
        org.assertj.core.api.Assertions.assertThat(insert.repeats())
            .extracting(QueryNormalizer.Repeat::count)
            .containsExactly(3);

        QueryNormalizer.NormalizedSql in = queryNormalizer.normalizeWithRepeats(CCJSqlParserUtil.parse(
            "SELECT `id` FROM `t` WHERE `a` IN (?, ?, ?, ?)"));
        org.assertj.core.api.Assertions.assertThat(in.repeats())
            .extracting(QueryNormalizer.Repeat::snippet, QueryNormalizer.Repeat::count)
            .containsExactly(org.assertj.core.api.Assertions.tuple("?", 4));
    }

    @Test
    public void noRepeatsReportedWhenNothingCollapses() throws JSQLParserException {
        QueryNormalizer.NormalizedSql normalized = queryNormalizer.normalizeWithRepeats(CCJSqlParserUtil.parse(
            "SELECT `id` FROM `t` WHERE `a` = ? AND `b` = ?"));

        org.assertj.core.api.Assertions.assertThat(normalized.repeats()).isEmpty();
    }

    private void assertNormalizesEqual(String variant, String canonical) throws JSQLParserException {
        assertEquals(
            queryNormalizer.normalize(CCJSqlParserUtil.parse(canonical)),
            queryNormalizer.normalize(CCJSqlParserUtil.parse(variant)));
    }
}
