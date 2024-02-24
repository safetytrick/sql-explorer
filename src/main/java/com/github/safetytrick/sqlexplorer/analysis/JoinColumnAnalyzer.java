package com.github.safetytrick.sqlexplorer.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import com.github.safetytrick.sqlexplorer.analysis.exception.UnsupportedSqlException;
import com.github.safetytrick.sqlexplorer.core.AnalyzedSql;
import com.github.safetytrick.sqlexplorer.core.JoinColumns;
import com.github.safetytrick.sqlexplorer.qualifier.ColumnAliasResolver;
import com.github.safetytrick.sqlexplorer.qualifier.DerivedTable;
import com.github.safetytrick.sqlexplorer.qualifier.SchemaAwareTableAliasResolver;
import com.github.safetytrick.sqlexplorer.qualifier.SimpleColumnAliasResolver;
import com.github.safetytrick.sqlexplorer.qualifier.TableAliasResolver;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

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
import net.sf.jsqlparser.statement.replace.Replace;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.FromItemVisitorAdapter;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.LateralSubSelect;
import net.sf.jsqlparser.statement.select.ParenthesisFromItem;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SelectExpressionItem;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SelectItemVisitor;
import net.sf.jsqlparser.statement.select.SelectItemVisitorAdapter;
import net.sf.jsqlparser.statement.select.SelectVisitorAdapter;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.SubJoin;
import net.sf.jsqlparser.statement.select.SubSelect;
import net.sf.jsqlparser.statement.select.TableFunction;
import net.sf.jsqlparser.statement.select.ValuesList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.upsert.Upsert;

@Order(2)
@Component
public class JoinColumnAnalyzer implements SqlAnalyzer {

    private final ColumnTableResolver columnTableResolver;

    public JoinColumnAnalyzer() {
        this(ColumnTableResolver.unsupported());
    }

    @Inject
    public JoinColumnAnalyzer(ColumnTableResolver columnTableResolver) {
        this.columnTableResolver = columnTableResolver;
    }

    @Override
    public void analyze(Statement statement, AnalyzedSql.Builder builder) {
        JoinColumnVisitor joinColumnVisitor = new JoinColumnVisitor(builder.view().getSchema());

        List<JoinColumns> joinColumns = joinColumnVisitor.getJoinColumns(statement);

        builder.joinColumns(joinColumns);
    }

    private class JoinColumnVisitor extends StatementVisitorAdapter {
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
        public void visit(Select select) {
            if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
                throw new UnsupportedSqlException("with items list");
            }
            if (select.getSelectBody() != null) {
                select.getSelectBody().accept(new JoinColumnSelectVisitor());
            }
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
        public void visit(Delete delete) {
            visitJoins(delete.getTable(), delete.getJoins());
        }

        @Override
        public void visit(Update update) {
            visitJoins(update.getFromItem(), update.getJoins());
        }

        @Override
        public void visit(Insert insert) {
            if (insert.getSelect() != null) {
                visit(insert.getSelect());
            }
        }

        @Override
        public void visit(Replace replace) {
            throw new UnsupportedSqlException("replace");
        }

        @Override
        public void visit(Upsert upsert) {
            visit(upsert.getSelect());
        }


        private class JoinColumnSelectVisitor extends SelectVisitorAdapter {
            @Override
            public void visit(PlainSelect plainSelect) {
                visitJoins(plainSelect.getFromItem(), plainSelect.getJoins());
                // todo recurse into sub-selects

                if (plainSelect.getWhere() != null) {
                    plainSelect.getWhere().accept(new JoinInSelectExpressionVisitor());

                }
            }

            @Override
            public void visit(SetOperationList setOpList) {
                if (setOpList.getSelects() != null) {
                    for (SelectBody selectBody : setOpList.getSelects()) {
                        selectBody.accept(this);
                    }
                }
            }

            @Override
            public void visit(WithItem withItem) {
                throw new UnsupportedSqlException("with item");
            }

        }

        private class JoinInSelectExpressionVisitor extends ExpressionVisitorAdapter {

            private final ColumnAliasResolver columnAliasResolver;

            private JoinInSelectExpressionVisitor() {
                this.columnAliasResolver = new SimpleColumnAliasResolver(tableAliasResolver);
            }

            @Override
            public void visit(InExpression expr) {
                super.visit(expr);

                String leftColumn = columnAliasResolver.columnReference(expr.getLeftExpression());

                if (leftColumn != null && expr.getRightItemsList() instanceof SubSelect) {

                    DerivedTable derivedTable = new FromSubSelectVisitor().getDerivedTable(expr.getRightItemsList(SubSelect.class));

                    SubSelect subSelect = expr.getRightItemsList(SubSelect.class);

                    subSelect.getSelectBody().accept(new SelectVisitorAdapter() {
                        @Override
                        public void visit(PlainSelect plainSelect) {

                            for (SelectItem selectItem : plainSelect.getSelectItems()) {
                                selectItem.accept(new SelectItemVisitorAdapter() {
                                    @Override
                                    public void visit(SelectExpressionItem item) {


                                        String rightColumn = derivedTable.columnReference(item.getExpression());
                                        if (rightColumn != null) {
                                            joinColumns.add(JoinColumns.fromReferences(leftColumn, rightColumn));
                                        }
                                    }
                                });


                            }
                        }
                    });
                }
            }
        }

        private class JoinOnColumnsExpressionVisitor extends ExpressionVisitorAdapter {
            private final FromItem fromItem;
            private List<JoinColumns> joinColumns;
            private ColumnAliasResolver columnAliasResolver;

            public JoinOnColumnsExpressionVisitor(FromItem fromItem) {
                this.fromItem = fromItem;
            }

            List<JoinColumns> getJoinColumns(Join join) {
                joinColumns = new ArrayList<>();
                if (join.getOnExpression() != null) {
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
                    join.getOnExpression().accept(this);
                } if (join.getUsingColumns() != null) {
                    throw new UnsupportedSqlException("join using");
                }
                return joinColumns;
            }

            @Override
            public void visit(EqualsTo equalsTo) {
                String leftColumn = columnAliasResolver.columnReference(equalsTo.getLeftExpression());
                String rightColumn = columnAliasResolver.columnReference(equalsTo.getRightExpression());

                if (leftColumn != null && rightColumn != null) {
                    joinColumns.add(JoinColumns.fromReferences(leftColumn, rightColumn));
                }
            }

            @Override
            public void visit(SubSelect subSelect) {
                super.visit(subSelect);
            }

        }

        private class FromSubSelectVisitor extends FromItemVisitorAdapter {

            private DerivedTable derivedTable;

            public DerivedTable getDerivedTable(FromItem fromItem) {
                fromItem.accept(this);
                // this alias resolver should be added as a temp table and referencable from the table name it is given!!!
                return derivedTable;
            }

            @Override
            public void visit(Table table) { }

            @Override
            public void visit(SubSelect subSelect) {
                TableAliasResolver previous = tableAliasResolver;
                try {
                    // this is a terrifying hack, I need to fix scope handling for alias resolution, etc.
                    tableAliasResolver = new SchemaAwareTableAliasResolver(tableAliasResolver, subSelect, columnTableResolver);
                    if (derivedTable != null) {
                        throw new UnsupportedSqlException("nested subselect is not implemented");
                    }
                    ColumnAliasResolverImpl columnAliasResolverImpl = new ColumnAliasResolverImpl();
                    // todo handle scope in subselects for the join column analyzer
                    subSelect.getSelectBody().accept(columnAliasResolverImpl);
                    // the alias will be null for IN (select) expressions, we also shouldn't be adding these to the derived tables list.
                    derivedTable = new DerivedTable(subSelect.getAlias() != null ? subSelect.getAlias().getName() : "", columnAliasResolverImpl);
                } finally {
                    tableAliasResolver = previous;
                }


            }

            @Override
            public void visit(SubJoin subjoin) {
                throw new UnsupportedSqlException("subjoin");
            }

            @Override
            public void visit(LateralSubSelect lateralSubSelect) {
                // ignore
            }

            @Override
            public void visit(ValuesList valuesList) {
                // ignore
            }

            @Override
            public void visit(TableFunction valuesList) {
                throw new UnsupportedSqlException("table function");
            }

            @Override
            public void visit(ParenthesisFromItem aThis) {
                aThis.accept(this);
            }
        }

        private static class TableNamesInFromItemVisitor extends FromItemVisitorAdapter {
            List<String> tableNames;
            public List<String> getTableNames(FromItem fromItem) {
                tableNames = new ArrayList<>();
                fromItem.accept(this);
                return tableNames;
            }

            @Override
            public void visit(Table table) {
                tableNames.add(table.getName());
            }
        }

        private class ColumnAliasResolverImpl extends SelectVisitorAdapter implements SelectItemVisitor, ColumnAliasResolver {
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
            public void visit(PlainSelect plainSelect) {

                if (plainSelect.getFromItem() != null) {
                    tableNames.addAll(new TableNamesInFromItemVisitor()
                                          .getTableNames(plainSelect.getFromItem()));
                }

                if (plainSelect.getSelectItems() != null) {
                    for (SelectItem selectItem : plainSelect.getSelectItems()) {
                        selectItem.accept(this);
                    }
                }
            }

            @Override
            public void visit(SetOperationList setOpList) {
                throw new UnsupportedSqlException("set operation");
            }

            @Override
            public void visit(WithItem withItem) {
                throw new UnsupportedSqlException("with item");
            }

            @Override
            public void visit(AllColumns allColumns) {
                for (String tableName : tableNames) {
                    List<String> columnsForTable = columnTableResolver.getAllColumnNamesForTable(schema, tableName);
                    for (String column : columnsForTable) {
                        columnAliasMap.put(column, tableName + "." + column);
                    }
                }
            }

            @Override
            public void visit(AllTableColumns allTableColumns) {
                String tableName = tableAliasResolver.resolveTableName(allTableColumns.getTable());
                List<String> columns = columnTableResolver.getAllColumnNamesForTable(schema, tableName);
                for (String column : columns) {
                    columnAliasMap.put(column, tableName + "." + column);
                }
            }

            @Override
            public void visit(SelectExpressionItem selectExpressionItem) {
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
