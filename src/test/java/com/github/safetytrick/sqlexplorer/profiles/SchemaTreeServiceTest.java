package com.github.safetytrick.sqlexplorer.profiles;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.analysis.IgnoredSchemas;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryService;
import com.github.safetytrick.sqlexplorer.profiles.SchemaTree.SchemaNode;
import com.github.safetytrick.sqlexplorer.profiles.SchemaTree.TableNode;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaService;
import com.github.safetytrick.sqlexplorer.schema.TableColumn;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchemaTreeServiceTest {

    private SqlRepositoryService sqlRepositoryService;
    private SqlSchemaService sqlSchemaService;
    private SchemaTreeService service;

    @BeforeEach
    void setUp() {
        sqlRepositoryService = mock(SqlRepositoryService.class);
        sqlSchemaService = mock(SqlSchemaService.class);
        service = new SchemaTreeService(sqlRepositoryService, sqlSchemaService, new IgnoredSchemas());
        when(sqlRepositoryService.getAllAnalyzedSql()).thenReturn(Stream.of());
        when(sqlSchemaService.getTables()).thenReturn(List.of());
    }

    @Test
    void mergesQueryStatsWithMetadataColumnCounts() {
        when(sqlSchemaService.getTables()).thenReturn(List.of(
            structure("app", "users", 14),
            structure("app", "unqueried", 6)));
        when(sqlRepositoryService.getAllAnalyzedSql()).thenReturn(Stream.of(
            profile("q1", false, ref("app", "users")),
            profile("q2", false, ref("app", "users"), ref("app", "ghost_table"))));

        List<TableNode> tables = schema("app").tables();

        assertThat(tables)
            .extracting(TableNode::name, TableNode::columnCount, TableNode::queryCount)
            .containsExactly(
                tuple("ghost_table", null, 1),
                tuple("unqueried", 6, 0),
                tuple("users", 14, 2));
    }

    @Test
    void warnFlagsTablesTouchedByAnalyzerFindings() {
        when(sqlRepositoryService.getAllAnalyzedSql()).thenReturn(Stream.of(
            profile("clean", false, ref("app", "users")),
            profile("warned", true, ref("app", "sessions"), ref("app", "users"))));

        assertThat(schema("app").tables())
            .extracting(TableNode::name, TableNode::warn)
            .containsExactly(tuple("sessions", true), tuple("users", true));
    }

    @Test
    void selfJoinCountsTheQueryOnce() {
        when(sqlRepositoryService.getAllAnalyzedSql()).thenReturn(Stream.of(
            profile("hierarchy", false, ref("app", "groups"), ref("app", "groups"))));

        assertThat(schema("app").tables())
            .extracting(TableNode::name, TableNode::queryCount)
            .containsExactly(tuple("groups", 1));
    }

    @Test
    void unresolvedSchemasAreOmittedAndIgnoredSchemasSortLast() {
        when(sqlRepositoryService.getAllAnalyzedSql()).thenReturn(Stream.of(
            profile("q1", false, ref("zebra", "t1"), ref(null, "orphan")),
            profile("q2", false, ref("sys", "x$statement_analysis")),
            profile("q3", false, ref("app", "t2"))));

        assertThat(service.getTree().schemas())
            .extracting(SchemaNode::name)
            .containsExactly("app", "zebra", "sys");
    }

    private SchemaNode schema(String name) {
        return service.getTree().schemas().stream()
            .filter(node -> node.name().equals(name))
            .findFirst().orElseThrow();
    }

    private static TableStructure structure(String schema, String table, int columns) {
        return new TableStructure(schema, table, "TABLE", null,
            List.of(), List.of(), Collections.nCopies(columns, (TableColumn) null));
    }

    private static TableReference ref(String schema, String table) {
        return new TableReference(schema, table, table, schema);
    }

    private static SqlProfile profile(String id, boolean warn, TableReference... tables) {
        SqlProfile profile = new SqlProfile();
        profile.setId(id);
        profile.setQuery("select 1");
        profile.setType("SELECT");
        profile.setDigest(id);
        profile.setNormalizedDigest(id);
        profile.setSchemaName("app");
        profile.setQueryActivityStatus(QueryActivityStatus.UNKNOWN);
        profile.setAnalysisFailed(false);
        profile.setTables(List.of(tables));
        profile.setMismatchedJoins(warn ? List.of(new JoinColumns(null, null)) : List.of());
        return profile;
    }
}
