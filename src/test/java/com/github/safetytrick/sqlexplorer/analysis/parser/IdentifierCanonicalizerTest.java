package com.github.safetytrick.sqlexplorer.analysis.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdentifierCanonicalizerTest {

    /**
     * The case this exists for. MySQL's digest text backticks every identifier;
     * a human types none. Both are the same query and must hash alike.
     */
    @Test
    void agreesBetweenMysqlsRenderingAndAHandWrittenOne() {
        String mysql = "SELECT `id` FROM `orders` WHERE `total` > ?";
        String human = "select id from orders where total > ?";

        assertThat(IdentifierCanonicalizer.canonicalize(mysql))
            .isEqualTo(IdentifierCanonicalizer.canonicalize(human));
    }

    @Test
    void ignoresCaseInIdentifiersAndKeywordsAlike() {
        assertThat(IdentifierCanonicalizer.canonicalize("SELECT ID FROM ORDERS"))
            .isEqualTo(IdentifierCanonicalizer.canonicalize("select id from orders"));
    }

    /**
     * A backtick or a capital inside a string literal is data. Rewriting it
     * would merge two statements that differ in a value, which is the opposite
     * of what normalization is for.
     */
    @Test
    void leavesStringLiteralsAlone() {
        assertThat(IdentifierCanonicalizer.canonicalize("select * from t where name = 'Bob'"))
            .isEqualTo("select * from t where name = 'Bob'");
        assertThat(IdentifierCanonicalizer.canonicalize("select * from t where name = 'A' or name = 'a'"))
            .contains("'A'").contains("'a'");
    }

    @Test
    void doesNotTreatALiteralsContentsAsAnIdentifier() {
        // the backtick here is inside the string; stripping it would change the value
        assertThat(IdentifierCanonicalizer.canonicalize("select * from t where s = 'a`b'"))
            .isEqualTo("select * from t where s = 'a`b'");
    }

    @Test
    void handlesBothEscapeConventionsForAQuote() {
        assertThat(IdentifierCanonicalizer.canonicalize("select 'it''s A'"))
            .isEqualTo("select 'it''s A'");
        assertThat(IdentifierCanonicalizer.canonicalize("select 'it\\'s A'"))
            .isEqualTo("select 'it\\'s A'");
    }

    @Test
    void copesWithAnUnterminatedLiteralRatherThanThrowing() {
        assertThat(IdentifierCanonicalizer.canonicalize("select * from t where s = 'oops"))
            .isEqualTo("select * from t where s = 'oops");
    }

    @Test
    void isIdempotent() {
        String once = IdentifierCanonicalizer.canonicalize("SELECT `id` FROM `orders`");
        assertThat(IdentifierCanonicalizer.canonicalize(once)).isEqualTo(once);
    }

    @Test
    void passesNullThrough() {
        assertThat(IdentifierCanonicalizer.canonicalize(null)).isNull();
    }
}
