package com.github.safetytrick.sqlexplorer.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.analysis.resolver.ColumnAliasResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.ColumnTableResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.DerivedTable;
import com.github.safetytrick.sqlexplorer.analysis.resolver.SchemaAwareTableAliasResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.SimpleColumnAliasResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.TableAliasResolver;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.FromItemVisitorAdapter;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SelectItemVisitor;
import net.sf.jsqlparser.statement.select.SelectVisitorAdapter;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;

/**
 * What a derived table — a subquery in FROM or JOIN — projects, so that a
 * column qualified by its alias resolves to the table the column came from.
 *
 * <p>Shared by the analyzers that resolve such columns. It lived inside
 * {@link JoinColumnAnalyzer}, so only joins could see through a derived
 * table, and the same {@code r_global.id} that a join resolved to its table
 * was recorded in the select list against {@code dual} (sqlx-03r).
 */
final class DerivedTables {

    private DerivedTables() {
    }

    /**
     * The derived table a subquery declares, its columns resolved in the
     * subquery's own scope, falling back to {@code enclosing}.
     */
    static DerivedTable of(ParenthesedSelect subSelect, TableAliasResolver enclosing,
                           ColumnTableResolver columnTableResolver, String schema) {
        // A UNION's columns are named by its first branch, so that branch's
        // sources stand for them — resolved in that branch's scope alone, as
        // the union's would put every branch's tables in reach of a bare name.
        Select body = subSelect.getSelect();
        while (body instanceof SetOperationList union && union.getSelects() != null && !union.getSelects().isEmpty()) {
            body = union.getSelects().getFirst();
        }
        TableAliasResolver scope = new SchemaAwareTableAliasResolver(enclosing, body, columnTableResolver);
        Projection projection = new Projection(scope, columnTableResolver, schema);
        body.accept(projection, null);
        // the alias is null for IN (select) expressions
        return new DerivedTable(subSelect.getAlias() != null ? subSelect.getAlias().getName() : "", projection);
    }

    /** Registers each derived table among a select's FROM and JOIN items with {@code resolver}. */
    static void register(PlainSelect plainSelect, TableAliasResolver resolver,
                         ColumnTableResolver columnTableResolver, String schema) {
        List<FromItem> items = new ArrayList<>();
        if (plainSelect.getFromItem() != null) {
            items.add(plainSelect.getFromItem());
        }
        if (plainSelect.getJoins() != null) {
            plainSelect.getJoins().forEach(join -> items.add(join.getRightItem()));
        }
        for (FromItem item : items) {
            if (item instanceof ParenthesedSelect subSelect && subSelect.getAlias() != null) {
                resolver.addDerivedTable(of(subSelect, resolver, columnTableResolver, schema));
            }
        }
    }

    private static final class TableNamesInFromItem extends FromItemVisitorAdapter<Void> {
        private final List<String> tableNames = new ArrayList<>();

        static List<String> of(FromItem fromItem) {
            TableNamesInFromItem visitor = new TableNamesInFromItem();
            fromItem.accept(visitor);
            return visitor.tableNames;
        }

        @Override
        public <S> Void visit(Table table, S context) {
            tableNames.add(table.getName());
            return null;
        }
    }

    /** A subquery's output column names, each mapped to the source column it projects. */
    private static final class Projection extends SelectVisitorAdapter<Void>
        implements SelectItemVisitor<Void>, ColumnAliasResolver {

        private final Map<String, String> columnAliasMap = new HashMap<>();
        private final TableAliasResolver scope;
        private final ColumnTableResolver columnTableResolver;
        private final String schema;
        private final SimpleColumnAliasResolver simpleColumnAliasResolver;
        private final List<String> tableNames = new ArrayList<>();

        private Projection(TableAliasResolver scope, ColumnTableResolver columnTableResolver, String schema) {
            this.scope = scope;
            this.columnTableResolver = columnTableResolver;
            this.schema = schema;
            this.simpleColumnAliasResolver = new SimpleColumnAliasResolver(scope);
        }

        @Override
        public String columnReference(Expression expression) {
            String col = simpleColumnAliasResolver.columnReference(expression);
            if (col == null) {
                return null;
            }
            return columnAliasMap.getOrDefault(key(col.substring(col.lastIndexOf('.') + 1)), col);
        }

        /**
         * The source column a reference to this derived table names, or
         * null when its select list does not project that name.
         *
         * <p>Keyed by normalized name, as MySQL compares column names: the
         * select list's bare {@code NAME} is the ON clause's
         * {@code `name`}. A miss used to return the bare column name, which
         * callers then read as a table — a join recorded against a table
         * called {@code name} (sqlx-9xg).
         */
        @Override
        public String columnReference(Column column) {
            return columnAliasMap.get(key(column.getColumnName()));
        }

        private static String key(String columnName) {
            return NameNormalizer.normalize(columnName);
        }

        @Override
        public <S> Void visit(PlainSelect plainSelect, S context) {
            if (plainSelect.getFromItem() != null) {
                tableNames.addAll(TableNamesInFromItem.of(plainSelect.getFromItem()));
            }
            if (plainSelect.getSelectItems() != null) {
                for (SelectItem<?> selectItem : plainSelect.getSelectItems()) {
                    selectItem.accept(this, context);
                }
            }
            return null;
        }

        @Override
        public <S> Void visit(ParenthesedSelect parenthesedSelect, S context) {
            parenthesedSelect.getSelect().accept(this, context);
            return null;
        }

        @Override
        public <S> Void visit(WithItem<?> withItem, S context) {
            throw new UnsupportedSqlException("with item");
        }

        @Override
        public <S> Void visit(SelectItem<? extends Expression> selectItem, S context) {
            Expression expression = selectItem.getExpression();
            // AllTableColumns (`t.*`) extends AllColumns (`*`), so check the subclass first.
            if (expression instanceof AllTableColumns allTableColumns) {
                visitAllTableColumns(allTableColumns);
            } else if (expression instanceof AllColumns) {
                visitAllColumns();
            } else {
                visitSelectExpressionItem(selectItem);
            }
            return null;
        }

        private void visitAllColumns() {
            for (String tableName : tableNames) {
                List<String> columnsForTable = columnTableResolver.getAllColumnNamesForTable(schema, tableName);
                for (String column : columnsForTable) {
                    columnAliasMap.put(key(column), tableName + "." + column);
                }
            }
        }

        private void visitAllTableColumns(AllTableColumns allTableColumns) {
            String tableName = scope.resolveTableName(allTableColumns.getTable());
            List<String> columns = columnTableResolver.getAllColumnNamesForTable(schema, tableName);
            for (String column : columns) {
                columnAliasMap.put(key(column), tableName + "." + column);
            }
        }

        private void visitSelectExpressionItem(SelectItem<? extends Expression> selectExpressionItem) {
            String columnReference = simpleColumnAliasResolver.columnReference(selectExpressionItem.getExpression());
            String name = null;
            if (selectExpressionItem.getAlias() != null) {
                name = selectExpressionItem.getAlias().getName();
            } else if (columnReference != null) {
                String[] split = columnReference.split("\\.");
                name = split[split.length - 1];
            }

            if (name != null) {
                columnAliasMap.put(key(name), columnReference);
            }
        }
    }
}
