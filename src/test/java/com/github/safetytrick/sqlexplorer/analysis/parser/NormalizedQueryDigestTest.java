package com.github.safetytrick.sqlexplorer.analysis.parser;

import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The normalized digest groups repeat-count variants of the same pattern:
 * two queries differing only in how many times a block repeats must hash
 * identically, while genuinely different queries must not.
 */
class NormalizedQueryDigestTest {

    private final NormalizedQueryDigest digest = new NormalizedQueryDigest(new QueryNormalizer(), new SqlParser());

    @Test
    void repeatedOrPredicatesHashLikeASingleOne() throws JSQLParserException {
        String two = "SELECT `id` FROM `member_name` WHERE `last_name` LIKE ? OR `last_name` LIKE ?";
        String five = "SELECT `id` FROM `member_name` WHERE `last_name` LIKE ? OR `last_name` LIKE ?"
            + " OR `last_name` LIKE ? OR `last_name` LIKE ? OR `last_name` LIKE ?";

        assertThat(digest.normalizedDigest(two)).isEqualTo(digest.normalizedDigest(five));
    }

    @Test
    void repeatedAndPredicatesCollapseInsideGroups() throws JSQLParserException {
        String small = """
            SELECT `id` FROM `member_name`
            WHERE (`first_name` LIKE ? OR `first_name` LIKE ?) AND (`last_name` LIKE ? OR `last_name` LIKE ?)
            """;
        String large = """
            SELECT `id` FROM `member_name`
            WHERE (`first_name` LIKE ? OR `first_name` LIKE ? OR `first_name` LIKE ?)
              AND (`last_name` LIKE ? OR `last_name` LIKE ? OR `last_name` LIKE ? OR `last_name` LIKE ?)
            """;

        assertThat(digest.normalizedDigest(small)).isEqualTo(digest.normalizedDigest(large));
    }

    @Test
    void batchedInsertRowsHashLikeASingleRow() throws JSQLParserException {
        String oneRow = "INSERT INTO `member_session` (`id`, `member_id`) VALUES (?, ?)";
        String threeRows = "INSERT INTO `member_session` (`id`, `member_id`) VALUES (?, ?), (?, ?), (?, ?)";

        assertThat(digest.normalizedDigest(oneRow)).isEqualTo(digest.normalizedDigest(threeRows));
    }

    @Test
    void repeatedCaseArmsHashLikeOne() throws JSQLParserException {
        String one = "SELECT CASE WHEN `type` = ? THEN ? ELSE ? END FROM `branch`";
        String three = "SELECT CASE WHEN `type` = ? THEN ? WHEN `type` = ? THEN ? WHEN `type` = ? THEN ? ELSE ? END FROM `branch`";

        assertThat(digest.normalizedDigest(one)).isEqualTo(digest.normalizedDigest(three));
    }

    @Test
    void inListLengthDoesNotChangeTheDigest() throws JSQLParserException {
        String one = "SELECT `id` FROM `branch` WHERE `type` IN (?)";
        String many = "SELECT `id` FROM `branch` WHERE `type` IN (?, ?, ?, ?)";

        assertThat(digest.normalizedDigest(one)).isEqualTo(digest.normalizedDigest(many));
    }

    @Test
    void distinctLiteralsStayDistinct() throws JSQLParserException {
        String distinct = "SELECT `id` FROM `branch` WHERE `type` IN (1, 2, 3)";
        String collapsed = "SELECT `id` FROM `branch` WHERE `type` IN (1)";

        assertThat(digest.normalizedDigest(distinct)).isNotEqualTo(digest.normalizedDigest(collapsed));
    }

    @Test
    void differentPatternsStillHashDifferently() throws JSQLParserException {
        String byLastName = "SELECT `id` FROM `member_name` WHERE `last_name` LIKE ?";
        String byFirstName = "SELECT `id` FROM `member_name` WHERE `first_name` LIKE ?";

        assertThat(digest.normalizedDigest(byLastName)).isNotEqualTo(digest.normalizedDigest(byFirstName));
    }

    @Test
    void digestedQueryCarriesTheNormalizedSqlAndRepeats() throws JSQLParserException {
        NormalizedQueryDigest.DigestedQuery digested = digest.digestedQuery(
            "SELECT `id` FROM `member_name` WHERE `last_name` LIKE ? OR `last_name` LIKE ? OR `last_name` LIKE ?");

        assertThat(digested.normalizedSql()).containsOnlyOnce("`last_name` LIKE ?");
        assertThat(digested.repeats())
            .extracting(QueryNormalizer.Repeat::snippet, QueryNormalizer.Repeat::count)
            .containsExactly(org.assertj.core.api.Assertions.tuple("`last_name` LIKE ?", 3));
        assertThat(digested.digest()).isEqualTo(digest.normalizedDigest(
            "SELECT `id` FROM `member_name` WHERE `last_name` LIKE ?"));
    }
}
