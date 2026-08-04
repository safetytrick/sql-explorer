package com.github.safetytrick.sqlexplorer.model;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wire contract of the per-table queries payload: the legacy table
 * pages read {@code schema}/{@code name} to build their header and links, so
 * the JSON field names and values are asserted here. If this fails, the
 * "{@code .table_name} without a schema" symptom is server-side.
 */
class TableSerializationTest {

    @Test
    void tableSerializesSchemaAndName() {
        Table table = Table.builder()
            .name("permissions")
            .schema("app_main")
            .queries(List.of())
            .numberOfQueries(0)
            .sumOfQueryTime(0)
            .foreignKeyTables(List.of())
            .joinTables(List.of())
            .relatedTables(List.of())
            .build();

        String json = JsonMapper.builder().build().writeValueAsString(table);

        assertThat(json)
            .contains("\"schema\":\"app_main\"")
            .contains("\"name\":\"permissions\"");
    }
}
