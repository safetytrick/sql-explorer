package com.github.safetytrick.sqlexplorer.analysis.resolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.analysis.NameNormalizer;
import com.github.safetytrick.sqlexplorer.analysis.UnsupportedSqlException;
import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import com.github.safetytrick.sqlexplorer.schema.ViewColumn;
import com.github.safetytrick.sqlexplorer.schema.ViewDefinitionService;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ViewLineageResolver {

    private static final Logger log = LoggerFactory.getLogger(ViewLineageResolver.class);

    private ViewLineageResolver() { }

    public static List<ViewColumn> resolveColumns(String definition, String defaultSchema, SqlSchemaRepository schema) {
        if (definition == null || definition.isBlank()) {
            return List.of();
        }
        try {
            return resolveColumns(CCJSqlParserUtil.parse(definition), defaultSchema, schema);
        } catch (Exception e) {
            log.debug("Unparseable view definition (schema {}): {}", defaultSchema, e.getMessage());
            return List.of();
        }
    }

    public static List<ViewColumn> resolveColumns(Statement statement, String defaultSchema, SqlSchemaRepository schema) {
        if (!(statement instanceof PlainSelect plainSelect)) {
            return List.of();
        }

        ColumnTableResolver columnTableResolver = new ColumnTableResolver(schema);
        TableAliasResolver aliasResolver = new SchemaAwareTableAliasResolver(statement, columnTableResolver);
        List<TableReference> underlying = ViewDefinitionService.underlyingTables(statement, defaultSchema);

        List<ViewColumn> columns = new ArrayList<>();
        for (SelectItem<?> item : plainSelect.getSelectItems()) {
            Expression expression = item.getExpression();
            String alias = item.getAlias() == null ? null : NameNormalizer.normalize(item.getAlias().getName());

            // AllTableColumns extends AllColumns: check the specific shape first
            if (expression instanceof AllTableColumns star) {
                String resolved = resolveQuietly(aliasResolver, star);
                expandStar(resolved == null ? List.of() : List.of(toReference(resolved, defaultSchema)),
                    columnTableResolver, columns);
            } else if (expression instanceof AllColumns) {
                expandStar(underlying, columnTableResolver, columns);
            } else if (expression instanceof Column column) {
                String name = alias != null ? alias : NameNormalizer.normalize(column.getColumnName());
                columns.add(new ViewColumn(name, resolve(column, aliasResolver, defaultSchema), false));
            } else if (expression != null) {
                String name = alias != null ? alias : expression.toString();
                columns.add(new ViewColumn(name, collectSources(expression, aliasResolver, defaultSchema), true));
            }
        }
        return columns;
    }

    private static String resolveQuietly(TableAliasResolver aliasResolver, AllTableColumns star) {
        try {
            return star.getTable() == null ? null : aliasResolver.resolveTableName(star.getTable());
        } catch (UnsupportedSqlException e) {
            return null;
        }
    }

    private static void expandStar(List<TableReference> tables, ColumnTableResolver resolver, List<ViewColumn> out) {
        for (TableReference table : tables) {
            for (String columnName : resolver.getAllColumnNamesForTable(
                    table.getNormalizedSchemaName(), table.getNormalizedTableName())) {
                out.add(new ViewColumn(NameNormalizer.normalize(columnName),
                    List.of(ColumnReference.newColumnReference(table, columnName)), false));
            }
        }
    }

    private static List<ColumnReference> resolve(Column column, TableAliasResolver aliasResolver, String defaultSchema) {
        try {
            String table = aliasResolver.resolveTableName(column);
            if (table == null) {
                return List.of();
            }
            return List.of(ColumnReference.newColumnReference(
                toReference(table, defaultSchema),
                NameNormalizer.normalize(column.getColumnName())));
        } catch (UnsupportedSqlException e) {
            return List.of();
        }
    }

    private static List<ColumnReference> collectSources(Expression expression, TableAliasResolver aliasResolver,
                                                        String defaultSchema) {
        Map<String, ColumnReference> distinct = new LinkedHashMap<>();
        expression.accept(new ExpressionVisitorAdapter<Void>() {
            @Override
            public <S> Void visit(Column column, S context) {
                for (ColumnReference reference : resolve(column, aliasResolver, defaultSchema)) {
                    distinct.putIfAbsent(reference.toStringNormalized(), reference);
                }
                return null;
            }
        });
        return new ArrayList<>(distinct.values());
    }

    private static TableReference toReference(String tableName, String defaultSchema) {
        String[] parts = tableName.split("\\.");
        String table = normalize(parts[parts.length - 1]);
        String schemaName = parts.length > 1
            ? normalize(parts[parts.length - 2])
            : normalize(defaultSchema);
        return TableReference.newTableReference(schemaName, table);
    }

    private static String normalize(String name) {
        return name == null ? null : NameNormalizer.normalize(name);
    }
}
