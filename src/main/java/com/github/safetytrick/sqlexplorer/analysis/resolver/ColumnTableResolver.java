package com.github.safetytrick.sqlexplorer.analysis.resolver;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.github.safetytrick.sqlexplorer.analysis.UnsupportedSqlException;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import com.github.safetytrick.sqlexplorer.schema.TableColumn;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;

/**
 * Resolves columns to tables against a single schema. Built per analysis call
 * by {@link ColumnTableResolverFactory} from the schema of the server the SQL
 * was captured on, rather than injected as a singleton.
 */
public class ColumnTableResolver {

    private final SqlSchemaRepository sqlSchemaService;

    public ColumnTableResolver(SqlSchemaRepository sqlSchemaService) {
        this.sqlSchemaService = sqlSchemaService;
    }

    public static ColumnTableResolver unsupported() {
        return new UnsupportedColumnTableResolver();
    }

    public String selectTableNameFromCandidates(List<String> tables, String column) {
        String schema = sqlSchemaService.findSchemaForTablesAndColumns(
            tables.stream()
                .map(TableReference::fromString)
                .collect(Collectors.toList()),
            Collections.singletonList(column));
        return selectTableNameFromCandidates(schema, tables, column);
    }

    // todo pass a better schema to this method
    protected String selectTableNameFromCandidates(String schema, List<String> tables, String column) {
        // Nothing to resolve against, so there is no question to fail at.
        // `SELECT CURRENT_TIMESTAMP` arrives as a bare `NOW` — MySQL's digest
        // canonicalises the keyword to its synonym, and there were never parens
        // to lose — which jsqlparser reads as a column with no FROM. Asking
        // which table it belongs to has no answer, and throwing turned that
        // into an analysisFailed on the profile: the only analyzer-thrown
        // failure in the whole corpus.
        //
        // `null` is already this method's word for "unresolved" — it is what
        // the bottom of the method returns when no candidate matched — and the
        // only caller treats null as unresolved and falls through to its parent
        // resolver. So this is the existing shape, not a new one.
        if (tables == null || tables.isEmpty()) {
            return null;
        }
        if (schema == null) {
            throw new UnsupportedSqlException("missing schema");
        }
        Set<TableReference> matchedTables = new HashSet<>(1);
        String missingTable = null;
        for (String table : tables) {
            // todo handle schema on table reference
            TableReference tableReference = TableReference.fromString(table);
            TableStructure tableStructure = sqlSchemaService.getTable(tableReference.withDefaultSchema(schema));
            if (tableStructure == null) {
                missingTable = table;
                continue;
            }
            tableStructure.columns().stream()
                .filter(x -> x.columnName().equalsIgnoreCase(column))
                .findFirst().ifPresent(x -> matchedTables.add(tableReference));

        }

        if (matchedTables.size() == 1) {
            return matchedTables.iterator().next().getFullName();
        }
        if (matchedTables.size() > 1) {
            throw new UnsupportedSqlException("multiple tables found for column: " + column + " tables: " + matchedTables + " in schema: " + schema);
        }

        if (missingTable != null) {
            throw new UnsupportedSqlException("could not find table: " + missingTable + " in schema: " + schema);
        }
        return null;
    }

    public List<String> getAllColumnNamesForTable(String schema, String table) {
        TableStructure tableStructure = sqlSchemaService.getTable(TableReference.newTableReference(schema, table));
        // A star can be projected off a derived-table alias or an otherwise
        // unknown name; there are no known columns to expand in that case.
        if (tableStructure == null) {
            return Collections.emptyList();
        }
        return tableStructure.columns()
            .stream()
            .map(TableColumn::columnName)
            .collect(Collectors.toList());
    }

    private static class UnsupportedColumnTableResolver extends ColumnTableResolver {

        public UnsupportedColumnTableResolver() {
            super(null);
        }

        @Override
        public String selectTableNameFromCandidates(List<String> tables, String column) {
            throw new UnsupportedSqlException("no effort made");
        }

        @Override
        public String selectTableNameFromCandidates(String schema, List<String> tables, String column) {
            throw new UnsupportedSqlException("no effort made");
        }
    }
}
