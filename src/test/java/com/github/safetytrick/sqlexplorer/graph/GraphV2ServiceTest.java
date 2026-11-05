package com.github.safetytrick.sqlexplorer.graph;

import java.math.BigDecimal;
import java.sql.JDBCType;
import java.util.List;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryService;
import com.github.safetytrick.sqlexplorer.schema.ForeignKey;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaService;
import com.github.safetytrick.sqlexplorer.schema.TableColumn;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphV2ServiceTest {

    private static final TableReference SESSIONS = ref("app", "sessions");
    private static final TableReference USERS = ref("app", "users");
    private static final TableReference ROLLUP = ref("reporting", "rollup");

    private SqlRepositoryService sqlRepositoryService;
    private SqlSchemaService sqlSchemaService;
    private GraphV2Service service;

    @BeforeEach
    void setUp() {
        sqlRepositoryService = mock(SqlRepositoryService.class);
        sqlSchemaService = mock(SqlSchemaService.class);
        service = new GraphV2Service(sqlRepositoryService, sqlSchemaService);

        // sessions.user_id INT -> users.id BIGINT (mismatch)
        when(sqlSchemaService.getTables()).thenReturn(List.of(
            structure("app", "sessions",
                List.of(fk(SESSIONS, "user_id", USERS, "id")),
                column("app", "sessions", "id", "BIGINT"), column("app", "sessions", "user_id", "INT")),
            structure("app", "users", List.of(),
                column("app", "users", "id", "BIGINT")),
            structure("app", "unrelated", List.of())));
        when(sqlRepositoryService.getAllAnalyzedSql()).thenAnswer(inv -> Stream.of(
            profile("q1", List.of(SESSIONS, USERS), metrics(100), join(SESSIONS, USERS)),
            // cross-schema partner appears as a node even though it is not in app
            profile("q2", List.of(SESSIONS, ROLLUP), metrics(7)),
            // self-join
            profile("q3", List.of(SESSIONS), metrics(1), join(SESSIONS, SESSIONS)),
            // unrelated schema only: ignored entirely
            profile("q4", List.of(ROLLUP), metrics(50))));
    }

    @Test
    void buildsNodesFromMetadataAndQueries() {
        GraphV2 graph = service.getSchemaGraph("app");

        assertThat(graph.nodes())
            .extracting(GraphV2.Node::table, GraphV2.Node::columnCount, GraphV2.Node::queryCount, GraphV2.Node::selfJoin)
            .containsExactlyInAnyOrder(
                tuple("sessions", 2, 3, true),
                tuple("users", 1, 1, false),
                tuple("unrelated", 0, 0, false),
                tuple("rollup", null, 1, false));
    }

    @Test
    void profileSetGraphSeedsNodesFromTheProfilesOnly() {
        GraphV2 graph = service.getGraphForProfiles(List.of(
            profile("q1", List.of(SESSIONS, USERS), metrics(100), join(SESSIONS, USERS))));

        // no schema seeding: "unrelated" and "rollup" don't appear
        assertThat(graph.nodes())
            .extracting(GraphV2.Node::table, GraphV2.Node::columnCount, GraphV2.Node::queryCount)
            .containsExactlyInAnyOrder(
                tuple("sessions", 2, 1),
                tuple("users", 1, 1));
        assertThat(graph.coQueryEdges()).hasSize(1);
        assertThat(graph.fkEdges())
            .extracting(GraphV2.FkEdge::fromTable, GraphV2.FkEdge::toTable)
            .containsExactly(tuple("sessions", "users"));
    }

    @Test
    void allTablesOfAScopedSchemaAddsItsUnqueriedTables() {
        GraphV2 graph = service.getGraphForProfiles(List.of(
            profile("q1", List.of(SESSIONS, USERS), metrics(100), join(SESSIONS, USERS))), List.of("APP"));

        // every table of the schema, but the counts and joins are still only the profiles'
        assertThat(graph.nodes())
            .extracting(GraphV2.Node::table, GraphV2.Node::queryCount)
            .containsExactlyInAnyOrder(
                tuple("sessions", 1),
                tuple("users", 1),
                tuple("unrelated", 0));
        assertThat(graph.joinEdges()).hasSize(1);
        assertThat(service.getGraphForProfiles(List.of(), List.of("other")).nodes()).isEmpty();
    }

    @Test
    void displayedViewsPullTheirBaseTablesInWithViewEdges() {
        when(sqlSchemaService.getTables()).thenReturn(List.of(
            view("app", "active_sessions", List.of(SESSIONS)),
            structure("app", "sessions", List.of(),
                column("app", "sessions", "id", "BIGINT"))));

        GraphV2 graph = service.getGraphForProfiles(List.of(
            profile("q1", List.of(ref("app", "active_sessions")), metrics(5))));

        assertThat(graph.nodes())
            .extracting(GraphV2.Node::table, GraphV2.Node::view, GraphV2.Node::queryCount)
            .containsExactlyInAnyOrder(
                tuple("active_sessions", true, 1),
                tuple("sessions", false, 0));
        assertThat(graph.viewEdges())
            .extracting(GraphV2.ViewEdge::fromTable, GraphV2.ViewEdge::toTable)
            .containsExactly(tuple("active_sessions", "sessions"));
    }

    @Test
    void fkEdgesCarryColumnLabelAndMismatch() {
        GraphV2 graph = service.getSchemaGraph("app");

        assertThat(graph.fkEdges())
            .extracting(GraphV2.FkEdge::fromTable, GraphV2.FkEdge::toTable, GraphV2.FkEdge::label, GraphV2.FkEdge::mismatch)
            .containsExactly(tuple("sessions", "users", "user_id→id", true));
    }

    @Test
    void joinEdgesComeFromAnalysisAndLeaveSelfJoinsOut() {
        GraphV2 graph = service.getSchemaGraph("app");

        // q1 joins sessions to users; q3's self-join is a node flag, not an edge;
        // q2 queries sessions with rollup but never joins them
        assertThat(graph.joinEdges())
            .extracting(GraphV2.JoinEdge::aTable, GraphV2.JoinEdge::bTable, GraphV2.JoinEdge::columns,
                GraphV2.JoinEdge::queries, GraphV2.JoinEdge::executions)
            .containsExactly(tuple("sessions", "users", List.of("id=id"), 1, 100L));
    }

    @Test
    void aQueryJoiningOnSeveralColumnsCountsOnceAndOrientsEachPair() {
        GraphV2 graph = service.getGraphForProfiles(List.of(
            // written users-first: the pair is still oriented sessions=users
            profile("q1", List.of(SESSIONS, USERS), metrics(4),
                join(USERS, "id", SESSIONS, "user_id"), join(SESSIONS, "id", USERS, "id"))));

        assertThat(graph.joinEdges())
            .extracting(GraphV2.JoinEdge::columns, GraphV2.JoinEdge::queries, GraphV2.JoinEdge::executions)
            .containsExactly(tuple(List.of("id=id", "user_id=id"), 1, 4L));
    }

    @Test
    void aForeignKeyIsJoinedOnlyWhenAQueryJoinsOnItsColumns() {
        // setUp's join is sessions.id = users.id: same tables, not the key's columns
        assertThat(service.getSchemaGraph("app").fkEdges())
            .extracting(GraphV2.FkEdge::joined).containsExactly(false);

        GraphV2 joined = service.getGraphForProfiles(List.of(
            profile("q1", List.of(SESSIONS, USERS), metrics(1), join(USERS, "id", SESSIONS, "user_id"))));
        assertThat(joined.fkEdges()).extracting(GraphV2.FkEdge::joined).containsExactly(true);
    }

    @Test
    void coQueryEdgesAggregateExecutionsPerPair() {
        GraphV2 graph = service.getSchemaGraph("app");

        assertThat(graph.coQueryEdges())
            .extracting(GraphV2.CoQueryEdge::aTable, GraphV2.CoQueryEdge::bTable,
                GraphV2.CoQueryEdge::queries, GraphV2.CoQueryEdge::executions)
            .containsExactlyInAnyOrder(
                tuple("sessions", "users", 1, 100L),
                tuple("sessions", "rollup", 1, 7L));
    }

    private static TableReference ref(String schema, String table) {
        return new TableReference(schema, table, table, schema);
    }

    private static TableStructure structure(String schema, String table, List<ForeignKey> fks, TableColumn... columns) {
        return new TableStructure(schema, table, "TABLE", null, fks, List.of(), List.of(columns), null, null);
    }

    private static TableStructure view(String schema, String table, List<TableReference> underlying) {
        return new TableStructure(schema, table, "VIEW", null, List.of(), List.of(), List.of(), underlying, null);
    }

    private static ForeignKey fk(TableReference from, String fromColumn, TableReference to, String toColumn) {
        return new ForeignKey.Builder()
            .constraintName("fk")
            .addFromAndTo(new ColumnReference(from, fromColumn, fromColumn),
                new ColumnReference(to, toColumn, toColumn))
            .build();
    }

    private static TableColumn column(String schema, String table, String name, String typeName) {
        return new TableColumn(schema, table, name, JDBCType.OTHER, typeName, 10, 0, 10,
            null, null, 0, 1, "NO", "NO", "NO", null, null);
    }

    private static JoinColumns join(TableReference left, TableReference right) {
        return join(left, "id", right, "id");
    }

    private static JoinColumns join(TableReference left, String leftColumn, TableReference right, String rightColumn) {
        return new JoinColumns(new ColumnReference(left, leftColumn, leftColumn),
            new ColumnReference(right, rightColumn, rightColumn), "INNER");
    }

    private static RuntimeMetrics metrics(long executions) {
        return new RuntimeMetrics(false, executions, 0L, 0L,
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
            0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
            "digest", null, null, BigDecimal.ONE, 0L, 0L, List.of());
    }

    private static SqlProfile profile(String id, List<TableReference> tables, RuntimeMetrics metrics, JoinColumns... joins) {
        SqlProfile profile = new SqlProfile();
        profile.setId(id);
        profile.setQuery("select 1");
        profile.setType("SELECT");
        profile.setDigest(id);
        profile.setNormalizedDigest(id);
        profile.setSchemaName("app");
        profile.setQueryActivityStatus(QueryActivityStatus.UNKNOWN);
        profile.setAnalysisFailed(false);
        profile.setRuntimeMetrics(metrics);
        profile.setTables(tables);
        profile.setJoinColumns(List.of(joins));
        profile.setMismatchedJoins(List.of());
        return profile;
    }
}
