package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(ignoredSchemas.ignoreAll(List.of(
            table("mysql", "user"), table("tdb", "member_session")))).isFalse();
    }

    @Test
    void noTablesIsNotIgnorable() {
        assertThat(ignoredSchemas.ignoreAll(List.of())).isFalse();
    }
}
