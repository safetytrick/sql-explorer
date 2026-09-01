package com.github.safetytrick.sqlexplorer.profiles;

import java.util.List;

import com.github.safetytrick.sqlexplorer.model.TableRollup;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wire contract of the view flag: the frontend's all/tables/views
 * filters read {@code view} on the schema-tree and table-rollup payloads. A
 * missing or renamed field degrades silently in the UI ("tables" shows
 * everything, "views" shows nothing), so the JSON name is asserted here.
 */
class ViewFlagSerializationTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void schemaTreeTableNodeSerializesTheViewFlag() {
        String json = mapper.writeValueAsString(
            new SchemaTree.TableNode("active_users", 3, 1, false, true, true, null));

        assertThat(json)
            .contains("\"view\":true")
            .contains("\"columnCount\":3")
            .contains("\"warn\":false")
            .contains("\"inSchema\":true");
    }

    @Test
    void tableRollupSerializesTheViewFlag() {
        String json = mapper.writeValueAsString(
            new TableRollup("active_users", 2, 10.0, "tdb", List.of(), true));

        assertThat(json)
            .contains("\"view\":true")
            .contains("\"schema\":\"tdb\"");
    }
}
