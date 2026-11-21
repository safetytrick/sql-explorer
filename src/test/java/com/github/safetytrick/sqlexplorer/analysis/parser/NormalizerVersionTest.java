package com.github.safetytrick.sqlexplorer.analysis.parser;

import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizerVersionTest {

    private final NormalizedQueryDigest digest =
        new NormalizedQueryDigest(new QueryNormalizer(), new SqlParser());

    @Test
    void everyDigestCarriesTheSchemeThatMadeIt() throws JSQLParserException {
        String value = digest.normalizedDigest("select id from orders where total > ?", "app");

        assertThat(value).startsWith("v" + QueryNormalizer.VERSION + ":");
    }

    @Test
    void keepsTheWholeHashAfterThePrefix() throws JSQLParserException {
        String value = digest.normalizedDigest("select id from orders", "app");
        String hash = value.substring(NormalizedQueryDigest.versionPrefix().length());

        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void fitsTheColumnItIsStoredIn() throws JSQLParserException {
        assertThat(digest.normalizedDigest("select 1", "app")).hasSizeLessThanOrEqualTo(72);
    }

    @Test
    void recognisesItsOwnDigestsAndRejectsOthers() throws JSQLParserException {
        String current = digest.normalizedDigest("select id from orders", "app");

        assertThat(NormalizedQueryDigest.isCurrentVersion(current)).isTrue();
        assertThat(NormalizedQueryDigest.isCurrentVersion(
            "018a13095946c2cf0aa1d10c697a9a49d55bb1ee37abc1ab4b9387133e1572ce")).isFalse();
        assertThat(NormalizedQueryDigest.isCurrentVersion("v0:abc")).isFalse();
        assertThat(NormalizedQueryDigest.isCurrentVersion(null)).isFalse();
    }

    @Test
    void stillDistinguishesQueriesWithinOneVersion() throws JSQLParserException {
        assertThat(digest.normalizedDigest("select id from orders", "app"))
            .isNotEqualTo(digest.normalizedDigest("select id from members", "app"));
    }
}
