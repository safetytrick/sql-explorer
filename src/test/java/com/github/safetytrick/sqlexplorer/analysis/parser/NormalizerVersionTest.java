package com.github.safetytrick.sqlexplorer.analysis.parser;

import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Digests say which normalization scheme produced them.
 *
 * <p>This became necessary the moment something stored a digest durably. The
 * corpus never needed it — it is rebuilt on every source load and so is
 * single-version by construction, which is how the scheme changed twice
 * without anyone noticing the gap.
 */
class NormalizerVersionTest {

    private final NormalizedQueryDigest digest =
        new NormalizedQueryDigest(new QueryNormalizer(), new SqlParser());

    @Test
    void everyDigestCarriesTheSchemeThatMadeIt() throws JSQLParserException {
        String value = digest.normalizedDigest("select id from orders where total > ?", "app");

        assertThat(value).startsWith("v" + QueryNormalizer.VERSION + ":");
    }

    /** A sha256 is 64 hex characters; the prefix is additional. */
    @Test
    void keepsTheWholeHashAfterThePrefix() throws JSQLParserException {
        String value = digest.normalizedDigest("select id from orders", "app");
        String hash = value.substring(NormalizedQueryDigest.versionPrefix().length());

        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
    }

    /**
     * The column was sized `varchar(72)` rather than `char(64)` specifically to
     * hold this prefix without a second migration. If the prefix ever outgrows
     * that, the migration has to come with it.
     */
    @Test
    void fitsTheColumnItIsStoredIn() throws JSQLParserException {
        assertThat(digest.normalizedDigest("select 1", "app")).hasSizeLessThanOrEqualTo(72);
    }

    @Test
    void recognisesItsOwnDigestsAndRejectsOthers() throws JSQLParserException {
        String current = digest.normalizedDigest("select id from orders", "app");

        assertThat(NormalizedQueryDigest.isCurrentVersion(current)).isTrue();
        // an unprefixed digest is pre-versioning, and therefore stale by definition
        assertThat(NormalizedQueryDigest.isCurrentVersion(
            "018a13095946c2cf0aa1d10c697a9a49d55bb1ee37abc1ab4b9387133e1572ce")).isFalse();
        assertThat(NormalizedQueryDigest.isCurrentVersion("v0:abc")).isFalse();
        assertThat(NormalizedQueryDigest.isCurrentVersion(null)).isFalse();
    }

    /** The prefix must not become the only thing distinguishing two queries. */
    @Test
    void stillDistinguishesQueriesWithinOneVersion() throws JSQLParserException {
        assertThat(digest.normalizedDigest("select id from orders", "app"))
            .isNotEqualTo(digest.normalizedDigest("select id from members", "app"));
    }
}
