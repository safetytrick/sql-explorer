package com.github.safetytrick.sqlexplorer.evidence;

import com.github.safetytrick.sqlexplorer.analysis.RegexTableExtractor;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShowStatementTablesTest {

    private final RegexTableExtractor extractor = new RegexTableExtractor();

    private String namesIn(String sql) {
        return extractor.extractTables("app", sql).stream()
            .map(TableReference::toString).sorted().toList().toString();
    }

    @Test
    void showCreateTableStillYieldsAName() {
        assertThat(namesIn("SHOW CREATE TABLE `contacts_dict_5f0a1b2c3d4e6`"))
            .as("the extractor reads the text correctly; ingest is what must ignore it")
            .contains("contacts_dict_5f0a1b2c3d4e6");
    }

    @Test
    void otherShowFormsYieldNamesTooSoTheRuleCannotBePerPhrasing() {
        assertThat(namesIn("SHOW COLUMNS FROM `orders`")).contains("orders");
        assertThat(namesIn("SHOW INDEX FROM `orders`")).contains("orders");
    }

    @Test
    void theHexSuffixIsAnArtifactOfThisCorpusNotAGeneralRule() {
        assertThat(namesIn("SELECT `a` FROM `audit_2024_0a1b2c3d4e5f6`"))
            .as("a real table matching the shape must survive, because the rule is not the shape")
            .contains("audit_2024_0a1b2c3d4e5f6");
    }

    @Test
    void showIsClassifiedAsNotWorthParsing() {
        assertThat(SqlEvidenceInputService.ignoredFirstTokens)
            .contains(SqlEvidenceInputService.firstToken("SHOW CREATE TABLE `x`"));
    }
}
