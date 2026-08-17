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

    /**
     * The session schema is part of the digest.
     *
     * <p>Framework SQL is written unqualified and run against every service's
     * schema, so one text is many statements. On the regression server
     * {@code SELECT COUNT(*) FROM `migrations` WHERE `created_at` IS NULL} ran
     * in ten schemas and arrived as ten performance-schema rows — one per
     * (schema, digest) — which collapsed into a single profile reporting one
     * of them. Which one was decided by a race, and differed between two
     * imports three hours apart.
     */
    @Test
    void theSameTextInDifferentSchemasDigestsDifferently() throws JSQLParserException {
        String sql = "SELECT COUNT(*) FROM `migrations` WHERE `created_at` IS NULL";

        assertThat(digest.normalizedDigest(sql, "app_main"))
            .isNotEqualTo(digest.normalizedDigest(sql, "del"));
    }

    /** And the same text in the same schema is still one statement. */
    @Test
    void theSameTextInTheSameSchemaDigestsIdentically() throws JSQLParserException {
        String sql = "SELECT COUNT(*) FROM `migrations` WHERE `created_at` IS NULL";

        assertThat(digest.normalizedDigest(sql, "app_main"))
            .isEqualTo(digest.normalizedDigest(sql, "app_main"));
    }

    /**
     * Scoping must not cost the repeat collapsing, which is the reason the
     * normalized digest exists at all: within one schema, variants of a
     * pattern still merge.
     */
    @Test
    void repeatVariantsStillMergeWithinASchema() throws JSQLParserException {
        String two = "SELECT `id` FROM `member_name` WHERE `last_name` LIKE ? OR `last_name` LIKE ?";
        String five = "SELECT `id` FROM `member_name` WHERE `last_name` LIKE ? OR `last_name` LIKE ?"
            + " OR `last_name` LIKE ? OR `last_name` LIKE ? OR `last_name` LIKE ?";

        assertThat(digest.normalizedDigest(two, "app_main"))
            .isEqualTo(digest.normalizedDigest(five, "app_main"));
    }

    /**
     * "No session schema" has one spelling in the hash. The raw row carries
     * null and {@code SqlProfile.schemaName} carries the {@code "dual"}
     * placeholder for the same condition; a statement that hashed differently
     * depending on which reached the digest first would appear twice.
     */
    @Test
    void everySpellingOfNoSessionSchemaDigestsTheSame() throws JSQLParserException {
        String sql = "SELECT COUNT(*) FROM `migrations` WHERE `created_at` IS NULL";

        String unscoped = digest.normalizedDigest(sql, null);

        assertThat(digest.normalizedDigest(sql, "")).isEqualTo(unscoped);
        assertThat(digest.normalizedDigest(sql, "   ")).isEqualTo(unscoped);
        assertThat(digest.normalizedDigest(sql, "dual")).isEqualTo(unscoped);
        assertThat(digest.normalizedDigest(sql)).isEqualTo(unscoped);
        assertThat(digest.normalizedDigest(sql, "app_main")).isNotEqualTo(unscoped);
    }

    /** Schema names compare case- and backtick-insensitively everywhere else. */
    @Test
    void theSchemaScopeIsNormalizedLikeEveryOtherSchemaName() throws JSQLParserException {
        String sql = "SELECT COUNT(*) FROM `migrations` WHERE `created_at` IS NULL";

        assertThat(digest.normalizedDigest(sql, "DEB")).isEqualTo(digest.normalizedDigest(sql, "app_main"));
        assertThat(digest.normalizedDigest(sql, "`app_main`")).isEqualTo(digest.normalizedDigest(sql, "app_main"));
    }

    /**
     * No pair of (schema, text) inputs can collide by running together. A
     * plain separator is not enough: a backtick-quoted MySQL identifier can
     * contain a newline, so {@code ("a", "b\nc")} and {@code ("a\nb", "c")}
     * hashed identically until the scope was length-prefixed.
     */
    @Test
    void theScopeCannotRunIntoTheStatementText() {
        assertThat(NormalizedQueryDigest.scopedDigest("a", "b\nc"))
            .isNotEqualTo(NormalizedQueryDigest.scopedDigest("a\nb", "c"));
    }
}
