package com.github.safetytrick.sqlexplorer.evidence;

import java.sql.JDBCType;
import java.util.List;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.analysis.IgnoredSchemas;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.commentary.TagLoader;
import com.github.safetytrick.sqlexplorer.commentary.TagsService;
import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;
import com.github.safetytrick.sqlexplorer.model.TableRollup;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaService;
import com.github.safetytrick.sqlexplorer.schema.TableColumn;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The tables listing narrows to tables having every requested column (sqlx-v86). */
class TablesWithColumnsTest {

    private static final TableReference NOTES = TableReference.newTableReference("app", "notes");
    private static final TableReference ATTACHMENTS = TableReference.newTableReference("app", "attachments");
    private static final TableReference ORDERS = TableReference.newTableReference("app", "orders");
    private static final TableReference UNKNOWN = TableReference.newTableReference("app", "gone");

    private SqlRepository repository;
    private SqlRepositoryService service;

    @BeforeEach
    void setUp() {
        SqlSchemaService schema = mock(SqlSchemaService.class);
        when(schema.getTable(NOTES)).thenReturn(structure("notes", "id", "Entity_Id", "entity_type", "body"));
        when(schema.getTable(ATTACHMENTS)).thenReturn(structure("attachments", "id", "entity_id", "entity_type"));
        when(schema.getTable(ORDERS)).thenReturn(structure("orders", "id", "entity_id", "total"));

        repository = mock(SqlRepository.class);
        when(repository.getAllTableReferences()).thenAnswer(inv -> Stream.of(NOTES, ATTACHMENTS, ORDERS, UNKNOWN));
        when(repository.getTableQueries(any())).thenAnswer(inv -> Stream.of());
        SqlRepositoryManager manager = mock(SqlRepositoryManager.class);
        when(manager.getSqlRepository("test")).thenReturn(repository);
        Environment environment = mock(Environment.class);
        when(environment.getName()).thenReturn("test");
        EnvironmentContext context = mock(EnvironmentContext.class);
        when(context.current()).thenReturn(environment);
        TagsService tags = mock(TagsService.class);
        TagLoader loader = mock(TagLoader.class);
        when(tags.getBulkTagLoaderByType("table")).thenReturn(loader);
        when(tags.getTableTagLoaderForSchema("app")).thenReturn(loader);

        service = new SqlRepositoryService(schema, manager, context, tags, new IgnoredSchemas());
    }

    private static TableStructure structure(String table, String... columns) {
        return new TableStructure("app", table, "TABLE", null, List.of(), List.of(),
            Stream.of(columns).map(column -> new TableColumn("app", table, column, JDBCType.BIGINT, "BIGINT",
                20, 0, 10, null, null, 0, 1, "NO", "NO", "NO", null, null)).toList(), null, null);
    }

    private static List<String> names(Stream<TableRollup> tables) {
        return tables.map(TableRollup::name).sorted().toList();
    }

    @Test
    void everyRequestedColumnMustBePresentInAnyCase() {
        assertThat(names(service.getAllTablesWithRollup(List.of("entity_id", "ENTITY_TYPE"))))
            .containsExactly("attachments", "notes");
        assertThat(names(service.getTablesForSchemaWithRollup("app", List.of("entity_id"))))
            .containsExactly("attachments", "notes", "orders");
    }

    @Test
    void noColumnsIsNoConstraintAndAnUnknownTableNeverMatches() {
        assertThat(names(service.getAllTablesWithRollup(null))).containsExactly("attachments", "gone", "notes", "orders");
        assertThat(names(service.getAllTablesWithRollup(List.of()))).hasSize(4);
        assertThat(names(service.getAllTablesWithRollup(List.of("id")))).doesNotContain("gone");
    }

    @Test
    void aTableWithoutTheColumnsIsNotRolledUp() {
        service.getAllTablesWithRollup(List.of("entity_type")).toList();

        verify(repository, never()).getTableQueries(ORDERS);
    }
}
