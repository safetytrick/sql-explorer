package com.github.safetytrick.sqlexplorer.qualifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.github.safetytrick.sqlexplorer.analysis.ColumnTableResolver;
import com.github.safetytrick.sqlexplorer.cleanup.NameNormalizer;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;

public class SchemaAwareTableAliasResolver implements TableAliasResolver {
    private final Expression expression;
    private final TableAliasResolver parent;
    private Map<String,String> resolvedAliases;
    private final Statement statement;
    private List<String> tableNames;
    private final ColumnTableResolver columnTableResolver;
    private Map<String,DerivedTable> derivedTables;

    public SchemaAwareTableAliasResolver(Statement statement, ColumnTableResolver columnTableResolver) {
        this.parent = null;
        this.statement = statement;
        this.columnTableResolver = columnTableResolver;
        this.expression = null;
    }

    public SchemaAwareTableAliasResolver(TableAliasResolver parent, Expression expression, ColumnTableResolver columnTableResolver) {
        this.parent = parent;
        this.statement = null;
        this.columnTableResolver = columnTableResolver;
        this.expression = expression;
    }

    public String resolveTableName(Column column) {
        // we probably have truncated sql that still parsed, see
        // com.github.safetytrick.sqlexplorer.analysis.WhereClauseAnalyzerTest.testSubSelectJoinInWhere
        if (column.getColumnName() == null) {
            return null;
        }

        // columns from temp tables aren't resolved correctly here
        String onlyTableName = getOnlyTableName();
        if (onlyTableName != null) {
            return onlyTableName;
        }

        if (column.getTable() != null && column.getTable().getName() != null) {
            if (derivedTables != null) {
                String tempTableAlias = NameNormalizer.normalize(column.getTable().getName());
                if (derivedTables.containsKey(tempTableAlias)) {
                    // todo fix this up
                    return derivedTables.get(tempTableAlias).columnReference(column).split("\\.")[0];
                }
            }

            String tableName = resolveTableName(column.getTable());
            if (tableName != null) {
                return tableName;
            }
        }

        if (derivedTables != null) {
            for (Map.Entry<String, DerivedTable> entry : derivedTables.entrySet()) {
                String columnReference = entry.getValue().columnReference(column);
                if (columnReference != null) {
                    // todo does this handle schema names?
                    return columnReference.split("\\.")[0];
                }

            }
        }

        List<String> filteredNames = tableNames.stream().map(NameNormalizer::normalize)
            .collect(Collectors.toList());

        String columnName = column.getColumnName().replace("`", "");
        // todo pass the actual schema down
        String tableName = null;
        if (column.getTable() == null) {
            tableName = columnTableResolver.selectTableNameFromCandidates(filteredNames, columnName);
        }
        if (tableName == null && parent != null) {
            tableName = parent.resolveTableName(column);
        }
        return tableName;
    }

    public String resolveTableName(Table table) {
        return resolveTableName(table.getFullyQualifiedName());
    }

    @Override
    public boolean hasAlias(String tableName) {
        initMappings();

        String alias = resolvedAliases.get(tableName.toLowerCase());
        return alias != null && !alias.equalsIgnoreCase(tableName);
    }

    @Override
    public void addDerivedTable(DerivedTable derivedTable) {
        if (derivedTables == null) {
            derivedTables = new HashMap<>(2);
        }
        derivedTables.put(NameNormalizer.normalize(derivedTable.alias()), derivedTable);
    }

    private String resolveTableName(String aliasOrName) {
        initMappings();
        String tableName = resolvedAliases.getOrDefault(aliasOrName.toLowerCase(), aliasOrName);
        if (tableNames.contains(tableName)) {
            return tableName;
        }

        if (aliasOrName.startsWith("`")) {
            aliasOrName = NameNormalizer.normalize(aliasOrName);
            tableName = resolvedAliases.getOrDefault(aliasOrName, aliasOrName);
            if (tableNames.contains(tableName)) {
                return tableName;
            }
        }

        if (parent != null && parent.hasAlias(aliasOrName)) {
            return parent.resolveTableName(new Table(aliasOrName));
        }
        return null;
    }

    private void initMappings() {
        if (resolvedAliases == null) {
            if (statement != null) {
                resolvedAliases = new ExtendedTableNamesFinder().resolveAliases(statement);
                if (tableNames == null) {

                    tableNames = resolvedAliases.values().stream().distinct().collect(Collectors.toList());
                }
            }
            if (expression != null) {
                resolvedAliases = new ExtendedTableNamesFinder().resolveAliases(expression);
                if (tableNames == null) {

                    tableNames = resolvedAliases.values().stream()
                        .filter(name -> parent == null || !parent.hasAlias(name))
                        .distinct().collect(Collectors.toList());
                }
            }
        }
    }

    private String getOnlyTableName() {
        initMappings();

        if (tableNames.size() == 1  && parent == null) {
            return tableNames.get(0);
        }
        return null;
    }
}
