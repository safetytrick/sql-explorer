package com.github.safetytrick.sqlexplorer.evidence;

import com.github.safetytrick.sqlexplorer.analysis.RegexTableExtractor;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A SHOW names the table it is asking about, never one it uses.
 *
 * <p>This test is really about the extractor, because the extractor is the
 * only thing that reads a SHOW — the statement is in {@code
 * ignoredFirstTokens}, so nothing parses it — and nothing corroborates what it
 * finds. Its DDL patterns match the {@code CREATE TABLE} sitting inside
 * {@code SHOW CREATE TABLE `x`}, which is how 162 tables that do not exist
 * reached the schema tree: 19% of it, all materialised-view rebuild artifacts
 * named {@code <view>_<hex timestamp>} (sqlx-1mm).
 *
 * <p>The extractor is left as it is — it is doing its job, and the names it
 * finds are correct readings of the text. The judgement that a SHOW's tables
 * are not evidence belongs to ingest, which is where they are dropped. These
 * cases pin what the extractor sees, so the reason the drop is needed cannot
 * quietly disappear.
 */
class ShowStatementTablesTest {

    private final RegexTableExtractor extractor = new RegexTableExtractor();

    private String namesIn(String sql) {
        return extractor.extractTables("app", sql).stream()
            .map(TableReference::toString).sorted().toList().toString();
    }

    /** The exact corpus shape, verbatim. */
    @Test
    void showCreateTableStillYieldsAName() {
        assertThat(namesIn("SHOW CREATE TABLE `contacts_dict_5f0a1b2c3d4e6`"))
            .as("the extractor reads the text correctly; ingest is what must ignore it")
            .contains("contacts_dict_5f0a1b2c3d4e6");
    }

    /**
     * Not only the CREATE form. `SHOW COLUMNS FROM x` matches the plain FROM
     * pattern, so the rule has to be about the statement rather than about one
     * phrasing of it.
     */
    @Test
    void otherShowFormsYieldNamesTooSoTheRuleCannotBePerPhrasing() {
        assertThat(namesIn("SHOW COLUMNS FROM `orders`")).contains("orders");
        assertThat(namesIn("SHOW INDEX FROM `orders`")).contains("orders");
    }

    /**
     * The reason a name pattern was rejected: `_<13 hex>` describes this
     * corpus's artifacts, not the category. A real table may look like one,
     * and the next tool's scheme will not.
     */
    @Test
    void theHexSuffixIsAnArtifactOfThisCorpusNotAGeneralRule() {
        assertThat(namesIn("SELECT `a` FROM `audit_2024_0a1b2c3d4e5f6`"))
            .as("a real table matching the shape must survive, because the rule is not the shape")
            .contains("audit_2024_0a1b2c3d4e5f6");
    }

    /** SHOW is skipped by first token, so the drop has one place to live. */
    @Test
    void showIsClassifiedAsNotWorthParsing() {
        assertThat(SqlEvidenceInputService.ignoredFirstTokens)
            .contains(SqlEvidenceInputService.firstToken("SHOW CREATE TABLE `x`"));
    }
}
