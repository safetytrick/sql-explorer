package com.github.safetytrick.sqlexplorer.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.github.safetytrick.sqlexplorer.analysis.resolver.ColumnAliasResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.ColumnTableResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.ColumnTableResolverFactory;
import com.github.safetytrick.sqlexplorer.analysis.resolver.DerivedTable;
import com.github.safetytrick.sqlexplorer.analysis.resolver.SchemaAwareTableAliasResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.SimpleColumnAliasResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.TableAliasResolver;
import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
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
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.FromItemVisitorAdapter;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.LateralSubSelect;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
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

    private final ColumnTableResolverFactory resolverFactory;

    public JoinColumnAnalyzer() {
        this(ColumnTableResolver.unsupported());
    }

    public JoinColumnAnalyzer(ColumnTableResolver columnTableResolver) {
        this.resolverFactory = schema -> columnTableResolver;
    }

    @Inject
    public JoinColumnAnalyzer(ColumnTableResolverFactory resolverFactory) {
        this.resolverFactory = resolverFactory;
    }

    @Override
    public void analyze(Statement statement, SqlProfile.Builder builder, SqlSchemaRepository schema) {
        JoinColumnVisitor joinColumnVisitor = new JoinColumnVisitor(builder.getSchemaName(), resolverFactory.forSchema(schema));

        List<JoinColumns> joinColumns = joinColumnVisitor.getJoinColumns(statement);

        builder.setJoinColumns(joinColumns);
    }

    private class JoinColumnVisitor extends StatementVisitorAdapter<Void> {
        private final String schema;
        private final ColumnTableResolver columnTableResolver;
        private List<JoinColumns> joinColumns;
        private TableAliasResolver tableAliasResolver;
        private final java.util.Set<String> cteNames = new java.util.HashSet<>();
        private CteProjections cteProjections = CteProjections.EMPTY;
        private Select statementSelect;

        public JoinColumnVisitor(String schema, ColumnTableResolver columnTableResolver) {
            super();
            this.schema = schema;
            this.columnTableResolver = columnTableResolver;
        }

        private void addJoin(JoinColumns join) {
            ColumnReference left = throughCte(join.getLeft());
            ColumnReference right = throughCte(join.getRight());
            if (left == null || right == null) {
                return;
            }
            if (left != join.getLeft() || right != join.getRight()) {
                joinColumns.add(new JoinColumns(left, right, join.getJoinType()));
                return;
            }
            joinColumns.add(join);
        }

        private ColumnReference throughCte(ColumnReference reference) {
            if (!referencesCte(reference)) {
                return reference;
            }
            String cte = reference.getTable().getTableName();
            Select body = statementSelect == null ? null : cteProjections.bodyOf(statementSelect, cte);
            if (body == null) {
                return null;
            }
            return cteProjections.resolve(cte, reference.getColumn(), columnTableResolver, body);
        }

        private boolean referencesCte(ColumnReference reference) {
            return reference != null && reference.getTable() != null
                && WithItems.isCte(cteNames, reference.getTable().getTableName());
        }


        List<JoinColumns> getJoinColumns(Statement statement) {
            joinColumns = new ArrayList<>();
            tableAliasResolver = new SchemaAwareTableAliasResolver(statement, columnTableResolver);
            statement.accept(this);
            return joinColumns;
        }

        @Override
        public <S> Void visit(Select select, S context) {
            cteNames.addAll(WithItems.namesOf(select));
            if (cteProjections.isEmpty()) {
                cteProjections = CteProjections.of(select);
                statementSelect = select;
            }
            for (Select cte : WithItems.bodiesOf(select)) {
                visit(cte, context);
            }

            select.accept(new JoinColumnSelectVisitor(), context);
            return null;
        }

        private void visitJoins(FromItem fromItem, List<Join> joins) {
            if (joins != null) {
                JoinOnColumnsExpressionVisitor visitor = new JoinOnColumnsExpressionVisitor(fromItem);
                for (Join join : joins) {
                    String joinType = joinType(join);
                    List<JoinColumns> joinColumns = visitor.getJoinColumns(join);
                    for (JoinColumns joinColumn : joinColumns) {
                        joinColumn.setJoinType(joinType);
                        addJoin(joinColumn);
                    }

                }

            }
        }

        private String joinType(Join join) {
            if (join.isLeft()) return "LEFT";
            if (join.isRight()) return "RIGHT";
            if (join.isFull()) return "FULL";
            if (join.isCross()) return "CROSS";
            if (join.isStraight()) return "STRAIGHT";
            if (join.isSimple()) return "IMPLICIT";
            return "INNER";
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
            public <S> Void visit(Select select, S context) {
                TableAliasResolver enclosing = tableAliasResolver;
                Select body = select;
                if (select instanceof ParenthesedSelect parenthesed) {
                    tableAliasResolver = new SchemaAwareTableAliasResolver(enclosing, parenthesed, columnTableResolver);
                    body = parenthesed.getSelect();
                }
                try {
                    body.accept(new JoinColumnSelectVisitor(), context);
                } finally {
                    tableAliasResolver = enclosing;
                }
                return null;
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
                // getOnExpression() throws on a join with no ON (e.g. CROSS JOIN)
                Collection<Expression> onExpressions = join.getOnExpressions();
                if (onExpressions != null && !onExpressions.isEmpty()) {
                    if (join.getRightItem() != null && !(join.getRightItem() instanceof Table)) {
                        DerivedTable derivedTable = new FromSubSelectVisitor().getDerivedTable(join.getRightItem());
                        tableAliasResolver.addDerivedTable(derivedTable);

                        columnAliasResolver = derivedTable;
                    }

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
                            ColumnReference.fromComponents(null, rightTableName, usingColumn.getColumnName()),
                            null
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
                return derivedTable;
            }

            @Override
            public <S> Void visit(Table table, S context) {
                return null;
            }

            @Override
            public <S> Void visit(ParenthesedSelect subSelect, S context) {
                if (derivedTable != null) {
                    throw new UnsupportedSqlException("nested subselect is not implemented");
                }
                derivedTable = DerivedTables.of(subSelect, tableAliasResolver, columnTableResolver, schema);
                return null;
            }

            @Override
            public <S> Void visit(LateralSubSelect lateralSubSelect, S context) {
                return null;
            }

            @Override
            public <S> Void visit(Values values, S context) {
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
    }


}
