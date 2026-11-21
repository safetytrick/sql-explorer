package com.github.safetytrick.sqlexplorer.analysis.resolver;

import java.util.List;

import com.github.safetytrick.sqlexplorer.analysis.TestSchemas;
import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.schema.LoadedSchema;
import com.github.safetytrick.sqlexplorer.schema.ViewColumn;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ViewLineageResolverTest {

    private final LoadedSchema schema = TestSchemas.all();

    private List<ViewColumn> resolve(String definition) {
        return ViewLineageResolver.resolveColumns(definition, TestSchemas.DEFAULT_SCHEMA, schema);
    }

    @Test
    void resolvesAliasedQualifiedColumns() {
        List<ViewColumn> columns = resolve(
            "select `ms`.`id` AS `session_id`, `ms`.`checkin_date` from `tdb`.`member_session` `ms`");

        assertThat(columns)
            .extracting(ViewColumn::name, ViewColumn::computed, c -> sources(c))
            .containsExactly(
                tuple("session_id", false, List.of("tdb.member_session.id")),
                tuple("checkin_date", false, List.of("tdb.member_session.checkin_date")));
    }

    @Test
    void resolvesUnqualifiedColumnsAcrossAJoin() {
        List<ViewColumn> columns = resolve("""
            select checkin_date, name
            from member_session ms join branch b on ms.branch_id = b.id
            """);

        assertThat(columns)
            .extracting(ViewColumn::name, c -> sources(c))
            .containsExactly(
                tuple("checkin_date", List.of("tdb.member_session.checkin_date")),
                tuple("name", List.of("tdb.branch.name")));
    }

    @Test
    void ambiguousColumnsDegradeToEmptySources() {
        List<ViewColumn> columns = resolve("""
            select created_on
            from member_session ms join branch b on ms.branch_id = b.id
            """);

        assertThat(columns)
            .extracting(ViewColumn::name, c -> sources(c))
            .containsExactly(tuple("created_on", List.of()));
    }

    @Test
    void expressionsAreComputedWithAllTheirSourceColumns() {
        List<ViewColumn> columns = resolve("""
            select concat(b.name, ' / ', ms.session_type) AS label
            from member_session ms join branch b on ms.branch_id = b.id
            """);

        assertThat(columns)
            .extracting(ViewColumn::name, ViewColumn::computed, c -> sources(c))
            .containsExactly(
                tuple("label", true, List.of("tdb.branch.name", "tdb.member_session.session_type")));
    }

    @Test
    void tableStarExpandsAgainstTheSchema() {
        List<ViewColumn> columns = resolve("""
            select ms.*, b.name AS branch_name
            from member_session ms join branch b on ms.branch_id = b.id
            """);

        assertThat(columns)
            .extracting(ViewColumn::name)
            .containsExactly("id", "member_id", "branch_id", "checkin_date", "checkout_date",
                "session_type", "main_class", "created_on", "branch_name");
        assertThat(sources(columns.getFirst())).containsExactly("tdb.member_session.id");
        assertThat(sources(columns.getLast())).containsExactly("tdb.branch.name");
    }

    @Test
    void bareStarExpandsEveryUnderlyingTable() {
        List<ViewColumn> columns = resolve("select * from branch");

        assertThat(columns)
            .extracting(ViewColumn::name)
            .containsExactly("id", "name", "type", "state", "created_on");
        assertThat(columns).allSatisfy(column ->
            assertThat(sources(column)).hasSize(1).allMatch(source -> source.startsWith("tdb.branch.")));
    }

    @Test
    void literalsHaveNoSources() {
        List<ViewColumn> columns = resolve("select 1 AS one, checkin_date from member_session");

        assertThat(columns)
            .extracting(ViewColumn::name, ViewColumn::computed, c -> sources(c))
            .containsExactly(
                tuple("one", true, List.of()),
                tuple("checkin_date", false, List.of("tdb.member_session.checkin_date")));
    }

    @Test
    void unionsKeepTableLevelLineageOnly() {
        assertThat(resolve("select id from member_session union select id from deleted_member_session"))
            .isEmpty();
    }

    @Test
    void unparseableDefinitionsYieldEmpty() {
        assertThat(resolve(null)).isEmpty();
        assertThat(resolve("not sql (")).isEmpty();
    }

    private static List<String> sources(ViewColumn column) {
        return column.sources().stream().map(ColumnReference::toStringNormalized).toList();
    }
}
