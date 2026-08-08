package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ignoreAll} decides whether a query is worth analyzing at all
 * ({@code SqlEvidenceInputService.processRow}), so its answer for "no tables"
 * is load-bearing rather than a corner case.
 */
class IgnoredSchemasTest {

    private final IgnoredSchemas ignoredSchemas = new IgnoredSchemas();

    private static TableReference table(String schema, String name) {
        return TableReference.newTableReference(schema, name);
    }

    @Test
    void ignoresTablesInSystemSchemas() {
        assertThat(ignoredSchemas.ignoreAll(List.of(table("mysql", "user")))).isTrue();
        assertThat(ignoredSchemas.ignoreAll(List.of(
            table("performance_schema", "events_statements_summary_by_digest"),
            table("sys", "schema_table_statistics")))).isTrue();
    }

    @Test
    void keepsAQueryTouchingAnythingOutsideThem() {
        assertThat(ignoredSchemas.ignoreAll(List.of(table("tdb", "member_session")))).isFalse();
        // one real table is enough — the query still says something about it
        assertThat(ignoredSchemas.ignoreAll(List.of(
            table("mysql", "user"), table("tdb", "member_session")))).isFalse();
    }

    /**
     * The regression this exists for: {@code allMatch} is vacuously true on an
     * empty stream, so a query whose tables the regex pre-scan failed to
     * extract was treated as "everything it touches is ignorable" and skipped
     * before the real parser ran. Not finding tables cheaply is a reason to
     * parse, not a reason to give up.
     */
    @Test
    void noTablesIsNotIgnorable() {
        assertThat(ignoredSchemas.ignoreAll(List.of())).isFalse();
    }
}
