package com.github.safetytrick.sqlexplorer.analysis.parser;

import java.util.List;

import com.github.safetytrick.sqlexplorer.analysis.parser.QueryNormalizer.Repeat;
import com.github.safetytrick.sqlexplorer.analysis.parser.RepeatLocator.Located;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RepeatLocatorTest {

    @Test
    void identifierTokensMatchExactly() {
        // whitespace and case are forgiven, identifier form is not: a
        // backticked snippet does not match an unbackticked identifier. In
        // production both sides come from the same normalizer serialization,
        // so their identifier forms agree.
        String unbackticked = "select id from t where a like ? and b = ?";

        List<Located> located = RepeatLocator.locate(unbackticked, List.of(new Repeat("`a` LIKE ?", 5)));

        assertThat(located).isEmpty();
    }

    @Test
    void locatesTokenSequencesIgnoringWhitespaceAndCase() {
        String formatted = "select id from t where `a` like ?\n    and `b` = ?";

        List<Located> located = RepeatLocator.locate(formatted, List.of(new Repeat("`a` LIKE ?", 5)));

        assertThat(located).hasSize(1);
        Located hit = located.getFirst();
        assertThat(formatted.substring(hit.start(), hit.end())).isEqualTo("`a` like ?");
        assertThat(hit.count()).isEqualTo(5);
    }

    @Test
    void sequentialRepeatsResolveToSuccessivePositions() {
        String text = "x = ? or y = ? or x = ?";

        List<Located> located = RepeatLocator.locate(text, List.of(
            new Repeat("x = ?", 2),
            new Repeat("y = ?", 3)));

        assertThat(located).hasSize(2);
        assertThat(located.get(0).start()).isEqualTo(0);
        assertThat(text.substring(located.get(1).start(), located.get(1).end())).isEqualTo("y = ?");
        // the second entry starts after the first match's tokens
        assertThat(located.get(1).start()).isGreaterThan(located.get(0).end());
    }

    @Test
    void unlocatableSnippetsAreDroppedNotMisattributed() {
        List<Located> located = RepeatLocator.locate("select 1", List.of(
            new Repeat("`gone` = ?", 4),
            new Repeat("1", 2)));

        assertThat(located).hasSize(1);
        assertThat(located.getFirst().count()).isEqualTo(2);
    }

    @Test
    void multiLineValuesRowsLocate() {
        String formatted = "insert\n    into\n        t (a, b)\n    values\n        (?, ?)";

        List<Located> located = RepeatLocator.locate(formatted, List.of(new Repeat("(?, ?)", 3)));

        assertThat(located).hasSize(1);
        assertThat(formatted.substring(located.getFirst().start(), located.getFirst().end())).isEqualTo("(?, ?)");
    }
}
