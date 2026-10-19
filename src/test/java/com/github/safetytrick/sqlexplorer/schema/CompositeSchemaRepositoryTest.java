package com.github.safetytrick.sqlexplorer.schema;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * An environment's servers usually carry the same schema — a primary and its
 * replicas — so the same table arrives once per server. Listing it once per
 * server put seventeen copies of a table into /tables/with-column on
 * production (sqlx-3js). The first server's definition is the one listed,
 * which is the one getTable() has always answered with.
 */
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

    /**
     * By name, a table in two schemas is two answers — that ambiguity is what
     * schema inference reads — but the same table on two servers is one.
     */
    @Test
    void byNameKeepsOtherSchemasButNotOtherServers() {
        assertThat(composite.getTablesByName(TableReference.newTableReference(null, "users")))
            .extracting(TableStructure::schema, TableStructure::remarks)
            .containsExactly(tuple("app", "primary"), tuple("audit", "replica"));
    }

    /** The shortcut that relies on it: a name found in one schema settles the schema at once. */
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
