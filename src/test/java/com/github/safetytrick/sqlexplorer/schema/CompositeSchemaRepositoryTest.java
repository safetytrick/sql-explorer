package com.github.safetytrick.sqlexplorer.schema;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class CompositeSchemaRepositoryTest {

    private static TableStructure table(String schema, String name, String remarks) {
        return new TableStructure(schema, name, "TABLE", remarks, List.of(), List.of(), List.of(), null, null);
    }

    private final CompositeSchemaRepository composite = new CompositeSchemaRepository(List.of(
        new LoadedSchema(List.of(table("app", "users", "primary"), table("app", "orders", "primary"))),
        new LoadedSchema(List.of(table("app", "users", "replica"), table("app", "orders", "replica"),
            table("audit", "users", "replica")))));

    @Test
    void eachTableIsListedOnceAsTheFirstServerDefinedIt() {
        assertThat(composite.getTables())
            .extracting(TableStructure::schema, TableStructure::name, TableStructure::remarks)
            .containsExactly(
                tuple("app", "users", "primary"),
                tuple("app", "orders", "primary"),
                tuple("audit", "users", "replica"));
    }

    @Test
    void byNameKeepsOtherSchemasButNotOtherServers() {
        assertThat(composite.getTablesByName(TableReference.newTableReference(null, "users")))
            .extracting(TableStructure::schema, TableStructure::remarks)
            .containsExactly(tuple("app", "primary"), tuple("audit", "replica"));
    }

    @Test
    void aTableInOneSchemaOnEveryServerSettlesTheSchema() {
        assertThat(composite.findSchemaForTablesAndColumns(
            List.of(TableReference.newTableReference(null, "orders")), List.of()))
            .isEqualTo("app");
    }

    @Test
    void lookupStillAnswersWithTheFirstServer() {
        assertThat(composite.getTable(TableReference.newTableReference("app", "users")).remarks())
            .isEqualTo("primary");
    }
}
