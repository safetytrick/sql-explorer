package com.github.safetytrick.sqlexplorer.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.analysis.resolver.ColumnAliasResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.ColumnTableResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.DerivedTable;
import com.github.safetytrick.sqlexplorer.analysis.resolver.SchemaAwareTableAliasResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.SimpleColumnAliasResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.TableAliasResolver;
import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import jakarta.inject.Inject;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.StatementVisitorAdapter;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.FromItemVisitorAdapter;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.LateralSubSelect;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SelectItemVisitor;
import net.sf.jsqlparser.statement.select.SelectItemVisitorAdapter;
import net.sf.jsqlparser.statement.select.SelectVisitorAdapter;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.TableFunction;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.upsert.Upsert;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Order(2)
@Component
class JoinColumnAnalyzer implements SqlAnalyzer {

    private final ColumnTableResolver columnTableResolver;

    public JoinColumnAnalyzer() {
        this(ColumnTableResolver.unsupported());
    }

    @Inject
    public JoinColumnAnalyzer(ColumnTableResolver columnTableResolver) {
        this.columnTableResolver = columnTableResolver;
    }

    @Override
    public void analyze(Statement statement, SqlProfile.Builder builder) {
        JoinColumnVisitor joinColumnVisitor = new JoinColumnVisitor(builder.getSchemaName());

        List<JoinColumns> joinColumns = joinColumnVisitor.getJoinColumns(statement);

        builder.setJoinColumns(joinColumns);
    }

    private class JoinColumnVisitor extends StatementVisitorAdapter<Void> {
        private final String schema;
        private List<JoinColumns> joinColumns;
        private TableAliasResolver tableAliasResolver;

        public JoinColumnVisitor(String schema) {
            super();
            this.schema = schema;
        }

        private void addJoin(JoinColumns join) {
            joinColumns.add(join);
        }


        List<JoinColumns> getJoinColumns(Statement statement) {
            joinColumns = new ArrayList<>();
            tableAliasResolver = new SchemaAwareTableAliasResolver(statement, columnTableResolver);
            statement.accept(this);
            return joinColumns;
        }

        @Override
        public <S> Void visit(Select select, S context) {
            if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
                throw new UnsupportedSqlException("with items list");
            }

            select.accept(new JoinColumnSelectVisitor(), context);
            return null;
        }

        private void visitJoins(FromItem fromItem, List<Join> joins) {
            if (joins != null) {
                JoinOnColumnsExpressionVisitor visitor = new JoinOnColumnsExpressionVisitor(fromItem);
                for (Join join : joins) {
                    List<JoinColumns> joinColumns = visitor.getJoinColumns(join);
                    for (JoinColumns joinColumn : joinColumns) {
                        addJoin(joinColumn);
                    }

                }

            }
        }

        @Override
        public <S> Void visit(Delete delete, S context) {
            visitJoins(delete.getTable(), delete.getJoins());
            return null;
        }

        @Override
        public <S> Void visit(Update update, S context) {
            visitJoins(update.getFromItem(), update.getJoins());
            return null;
        }

        @Override
        public <S> Void visit(Insert insert, S context) {
            if (insert.getSelect() != null) {
                visit(insert.getSelect(), context);
            }
            return null;
        }

        @Override
        public <S> Void visit(Upsert upsert, S context) {
            visit(upsert.getSelect(), context);
            return null;
        }


        private class JoinColumnSelectVisitor extends SelectVisitorAdapter<Void> {
            @Override
            public <S> Void visit(PlainSelect plainSelect, S context) {
                visitJoins(plainSelect.getFromItem(), plainSelect.getJoins());
                // todo recurse into sub-selects

                if (plainSelect.getWhere() != null) {
                    plainSelect.getWhere().accept(new JoinInSelectExpressionVisitor());

                }
                return null;
            }

            @Override
            public <S> Void visit(SetOperationList setOpList, S context) {
                if (setOpList.getSelects() != null) {
                    for (Select select : setOpList.getSelects()) {
                        select.accept(this, context);
                    }
                }
                return null;
            }

            @Override
            public <S> Void visit(WithItem<?> withItem, S context) {
                throw new UnsupportedSqlException("with item");
            }

        }

        private class JoinInSelectExpressionVisitor extends ExpressionVisitorAdapter<Void> {

            private final ColumnAliasResolver columnAliasResolver;

            private JoinInSelectExpressionVisitor() {
                this.columnAliasResolver = new SimpleColumnAliasResolver(tableAliasResolver);
            }

            @Override
            public <S> Void visit(InExpression expr, S context) {
                super.visit(expr, context);

                String leftColumn = columnAliasResolver.columnReference(expr.getLeftExpression());

                if (leftColumn != null && expr.getRightExpression() instanceof ParenthesedSelect subSelect) {

                    DerivedTable derivedTable = new FromSubSelectVisitor().getDerivedTable(subSelect);

                    subSelect.getSelect().accept(new SelectVisitorAdapter<Void>() {
                        @Override
                        public <S2> Void visit(PlainSelect plainSelect, S2 ctx) {

                            for (SelectItem<?> selectItem : plainSelect.getSelectItems()) {
                                selectItem.accept(new SelectItemVisitorAdapter<Void>() {
                                    @Override
                                    public <S3> Void visit(SelectItem<? extends Expression> item, S3 c) {
                                        String rightColumn = derivedTable.columnReference(item.getExpression());
                                        if (rightColumn != null) {
                                            joinColumns.add(JoinColumns.fromReferences(leftColumn, rightColumn));
                                        }
                                        return null;
                                    }
                                }, ctx);
                            }
                            return null;
                        }
                    }, context);
                }
                return null;
            }
        }

        private class JoinOnColumnsExpressionVisitor extends ExpressionVisitorAdapter<Void> {
            private final FromItem fromItem;
            private List<JoinColumns> joinColumns;
            private ColumnAliasResolver columnAliasResolver;

            public JoinOnColumnsExpressionVisitor(FromItem fromItem) {
                this.fromItem = fromItem;
            }

            List<JoinColumns> getJoinColumns(Join join) {
                joinColumns = new ArrayList<>();
                // jsqlparser 5.x exposes ON clauses as a collection; getOnExpression()
                // throws on a join with no ON (e.g. CROSS JOIN), so guard on the collection.
                Collection<Expression> onExpressions = join.getOnExpressions();
                if (onExpressions != null && !onExpressions.isEmpty()) {
                    if (join.getRightItem() != null && !(join.getRightItem() instanceof Table)) {
                        DerivedTable derivedTable = new FromSubSelectVisitor().getDerivedTable(join.getRightItem());
                        tableAliasResolver.addDerivedTable(derivedTable);

                        columnAliasResolver = derivedTable;
                    }

                    // todo I don't think this will handle two subselects joined together
                    if (!(fromItem instanceof Table)) {
                        DerivedTable derivedTable = new FromSubSelectVisitor().getDerivedTable(fromItem);
                        tableAliasResolver.addDerivedTable(derivedTable);

                        columnAliasResolver = derivedTable;
                    }

                    if (columnAliasResolver == null) {
                        columnAliasResolver = new SimpleColumnAliasResolver(tableAliasResolver);
                    }
                    for (Expression onExpression : onExpressions) {
                        onExpression.accept(this);
                    }
                } if (join.getUsingColumns() != null && !join.getUsingColumns().isEmpty()) {

                    String fromTableName = null, rightTableName = null;
                    if (fromItem instanceof Table fromTable) {
                        fromTableName = tableAliasResolver.resolveTableName(fromTable);
                    }
                    if (join.getRightItem() instanceof Table rightTable) {
                        rightTableName = tableAliasResolver.resolveTableName(rightTable);
                    }

                    if (fromTableName == null || rightTableName == null) {
                        throw new UnsupportedSqlException("unable to resolve tables for using expression");
                    }

                    for (Column usingColumn : join.getUsingColumns()) {
                        joinColumns.add(new JoinColumns(
                            ColumnReference.fromComponents(null, fromTableName, usingColumn.getColumnName()),
                            ColumnReference.fromComponents(null, rightTableName, usingColumn.getColumnName())
                        ));
                    }
                }
                return joinColumns;
            }

            @Override
            public <S> Void visit(EqualsTo equalsTo, S context) {
                String leftColumn = columnAliasResolver.columnReference(equalsTo.getLeftExpression());
                String rightColumn = columnAliasResolver.columnReference(equalsTo.getRightExpression());

                if (leftColumn != null && rightColumn != null) {
                    joinColumns.add(JoinColumns.fromReferences(leftColumn, rightColumn));
                }
                return null;
            }

            @Override
            public <S> Void visit(ParenthesedSelect parenthesedSelect, S context) {
                return super.visit(parenthesedSelect, context);
            }

        }

        private class FromSubSelectVisitor extends FromItemVisitorAdapter<Void> {

            private DerivedTable derivedTable;

            public DerivedTable getDerivedTable(FromItem fromItem) {
                fromItem.accept(this);
                // this alias resolver should be added as a temp table and referencable from the table name it is given!!!
                return derivedTable;
            }

            @Override
            public <S> Void visit(Table table, S context) {
                return null;
            }

            @Override
            public <S> Void visit(ParenthesedSelect subSelect, S context) {
                TableAliasResolver previous = tableAliasResolver;
                try {
                    // this is a terrifying hack, I need to fix scope handling for alias resolution, etc.
                    tableAliasResolver = new SchemaAwareTableAliasResolver(tableAliasResolver, subSelect, columnTableResolver);
                    if (derivedTable != null) {
                        throw new UnsupportedSqlException("nested subselect is not implemented");
                    }
                    ColumnAliasResolverImpl columnAliasResolverImpl = new ColumnAliasResolverImpl();
                    // todo handle scope in subselects for the join column analyzer
                    subSelect.getSelect().accept(columnAliasResolverImpl, context);
                    // the alias will be null for IN (select) expressions, we also shouldn't be adding these to the derived tables list.
                    derivedTable = new DerivedTable(subSelect.getAlias() != null ? subSelect.getAlias().getName() : "", columnAliasResolverImpl);
                } finally {
                    tableAliasResolver = previous;
                }
                return null;
            }

            @Override
            public <S> Void visit(LateralSubSelect lateralSubSelect, S context) {
                // ignore
                return null;
            }

            @Override
            public <S> Void visit(Values values, S context) {
                // ignore
                return null;
            }

            @Override
            public <S> Void visit(TableFunction tableFunction, S context) {
                throw new UnsupportedSqlException("table function");
            }

            @Override
            public <S> Void visit(ParenthesedFromItem parenthesedFromItem, S context) {
                parenthesedFromItem.getFromItem().accept(this);
                return null;
            }
        }

        private static class TableNamesInFromItemVisitor extends FromItemVisitorAdapter<Void> {
            List<String> tableNames;
            public List<String> getTableNames(FromItem fromItem) {
                tableNames = new ArrayList<>();
                fromItem.accept(this);
                return tableNames;
            }

            @Override
            public <S> Void visit(Table table, S context) {
                tableNames.add(table.getName());
                return null;
            }
        }

        private class ColumnAliasResolverImpl extends SelectVisitorAdapter<Void> implements SelectItemVisitor<Void>, ColumnAliasResolver {
            private final Map<String, String> columnAliasMap = new HashMap<>();
            private final SimpleColumnAliasResolver simpleColumnAliasResolver = new SimpleColumnAliasResolver(tableAliasResolver);
            private final List<String> tableNames = new ArrayList<>();

            @Override
            public String columnReference(Expression expression) {
                String col = simpleColumnAliasResolver.columnReference(expression);
                if (col == null) {
                    return null;
                }
                return columnAliasMap.getOrDefault(col.split("\\.", 2)[1], col);
            }

            @Override
            public String columnReference(Column column) {
                return columnAliasMap.getOrDefault(column.getColumnName(), column.getColumnName());
            }


            @Override
            public <S> Void visit(PlainSelect plainSelect, S context) {

                if (plainSelect.getFromItem() != null) {
                    tableNames.addAll(new TableNamesInFromItemVisitor()
                                          .getTableNames(plainSelect.getFromItem()));
                }

                if (plainSelect.getSelectItems() != null) {
                    for (SelectItem<?> selectItem : plainSelect.getSelectItems()) {
                        selectItem.accept(this, context);
                    }
                }
                return null;
            }

            @Override
            public <S> Void visit(SetOperationList setOpList, S context) {
                throw new UnsupportedSqlException("set operation");
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
                        columnAliasMap.put(column, tableName + "." + column);
                    }
                }
            }

            private void visitAllTableColumns(AllTableColumns allTableColumns) {
                String tableName = tableAliasResolver.resolveTableName(allTableColumns.getTable());
                List<String> columns = columnTableResolver.getAllColumnNamesForTable(schema, tableName);
                for (String column : columns) {
                    columnAliasMap.put(column, tableName + "." + column);
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
                    columnAliasMap.put(name, columnReference);
                }
            }
        }
    }


}
