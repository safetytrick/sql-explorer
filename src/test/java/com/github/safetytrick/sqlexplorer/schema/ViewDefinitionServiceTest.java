package com.github.safetytrick.sqlexplorer.schema;

import java.util.List;

import com.github.safetytrick.sqlexplorer.analysis.TestSchemas;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ViewDefinitionServiceTest {

    @Test
    void resolvesFullyQualifiedTables() {
        // information_schema.VIEWS stores definitions fully qualified and backticked
        List<TableReference> tables = ViewDefinitionService.parseUnderlyingTables(
            "select `s`.`id` AS `id` from `tdb`.`member_session` `s`", "tdb");

        assertThat(tables)
            .extracting(TableReference::getNormalizedSchemaName, TableReference::getNormalizedTableName)
            .containsExactly(tuple("tdb", "member_session"));
    }

    @Test
    void defaultsUnqualifiedTablesToTheViewSchema() {
        List<TableReference> tables = ViewDefinitionService.parseUnderlyingTables(
            "select s.id, b.name from member_session s join branch b on s.branch_id = b.id", "tdb");

        assertThat(tables)
            .extracting(TableReference::getNormalizedSchemaName, TableReference::getNormalizedTableName)
            .containsExactlyInAnyOrder(
                tuple("tdb", "member_session"),
                tuple("tdb", "branch"));
    }

    @Test
    void includesTablesFromSubqueriesAndUnions() {
        List<TableReference> tables = ViewDefinitionService.parseUnderlyingTables(
            """
            select id from member_session where branch_id in (select id from branch)
            union select id from deleted_member_session
            """, "tdb");

        assertThat(tables)
            .extracting(TableReference::getNormalizedTableName)
            .containsExactlyInAnyOrder("member_session", "branch", "deleted_member_session");
    }

    @Test
    void deduplicatesRepeatedReferences() {
        List<TableReference> tables = ViewDefinitionService.parseUnderlyingTables(
            "select a.id from member_session a join `tdb`.`member_session` b on a.id = b.id", "tdb");

        assertThat(tables).hasSize(1);
        assertThat(tables.getFirst().getNormalizedTableName()).isEqualTo("member_session");
    }

    @Test
    void unparseableOrMissingDefinitionsYieldEmpty() {
        assertThat(ViewDefinitionService.parseUnderlyingTables(null, "tdb")).isEmpty();
        assertThat(ViewDefinitionService.parseUnderlyingTables("  ", "tdb")).isEmpty();
        assertThat(ViewDefinitionService.parseUnderlyingTables("not sql at all (", "tdb")).isEmpty();
    }

    /** The DDL path (fromCreateTable) resolves the same dependency info as the information_schema path. */
    @Test
    void viewFixtureLoadsWithUnderlyingTables() {
        TableStructure view = TestSchemas.table("active_member_sessions");

        assertThat(view.type()).isEqualTo("VIEW");
        assertThat(view.underlyingTables())
            .extracting(TableReference::getNormalizedSchemaName, TableReference::getNormalizedTableName)
            .containsExactlyInAnyOrder(
                tuple("tdb", "member_session"),
                tuple("tdb", "branch"));
    }
}
