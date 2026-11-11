package com.github.safetytrick.sqlexplorer.analysis;

import java.util.Optional;

import com.github.safetytrick.sqlexplorer.analysis.resolver.ColumnTableResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.ColumnTableResolverFactory;
import com.github.safetytrick.sqlexplorer.analysis.resolver.SchemaAwareTableAliasResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.SimpleColumnAliasResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.TableAliasResolver;
import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.CriteriaComparison;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import jakarta.inject.Inject;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.StatementVisitorAdapter;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.FromItemVisitorAdapter;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.LateralSubSelect;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SelectVisitorAdapter;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.upsert.Upsert;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Order(3)
@Component
class WhereClauseAnalyzer implements SqlAnalyzer {

    private final ColumnTableResolverFactory resolverFactory;

    @Inject
    public WhereClauseAnalyzer(ColumnTableResolverFactory resolverFactory) {
        this.resolverFactory = resolverFactory;
    }

    /** Test/convenience: resolve against a fixed resolver regardless of schema. */
    public WhereClauseAnalyzer(ColumnTableResolver columnTableResolver) {
        this.resolverFactory = schema -> columnTableResolver;
    }

    public WhereClauseAnalyzer() {
        this(ColumnTableResolver.unsupported());
    }

    @Override
    public void analyze(Statement statement, SqlProfile.Builder builder, SqlSchemaRepository schema) {
        new WhereClauseVisitor(builder, resolverFactory.forSchema(schema)).visit(statement);
    }

    // The comparison kinds a criteria column is recorded under (CriteriaComparison.kind).
    static final String KIND_EQUALITY = "equality";
    static final String KIND_INEQUALITY = "inequality";
    static final String KIND_RANGE = "range";
    static final String KIND_IN = "in";
    static final String KIND_NOT_IN = "not in";
    static final String KIND_LIKE = "like";
    static final String KIND_NOT_LIKE = "not like";
    static final String KIND_NULL_CHECK = "null check";

    /**
     * Whether a LIKE pattern starts with a wildcard, when that can be read
     * from the statement: a string literal, or {@code CONCAT} whose first
     * argument is one ({@code CONCAT('%', ?)}). Anything else — above all
     * {@code ?}, which is what a digest leaves of every literal — is unknown
     * and answers empty. The pattern of a parameter is not guessed at.
     */
    static Optional<Boolean> leadingWildcard(Expression pattern) {
        if (pattern instanceof StringValue literal) {
            return startsWithWildcard(literal.getValue());
        }
        if (pattern instanceof Function function
            && "CONCAT".equalsIgnoreCase(function.getName())
            && function.getParameters() != null
            && !function.getParameters().isEmpty()) {
            return leadingWildcard(function.getParameters().getFirst());
        }
        return Optional.empty();
    }

    /** Empty for the empty string: CONCAT('', ?) starts wherever the next argument does. */
    private static Optional<Boolean> startsWithWildcard(String value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        char first = value.charAt(0);
        return Optional.of(first == '%' || first == '_');
    }

    private class WhereClauseVisitor extends StatementVisitorAdapter<Void> {
        private final SqlProfile.Builder builder;
        private final ColumnTableResolver columnTableResolver;
        private TableAliasResolver aliasResolver;
        private final WhereSelectVisitor selectVisitor = new WhereSelectVisitor();
        private final java.util.Set<String> cteNames = new java.util.HashSet<>();

        private WhereClauseVisitor(SqlProfile.Builder builder, ColumnTableResolver columnTableResolver) {
            super();
            this.builder = builder;
            this.columnTableResolver = columnTableResolver;
        }

        void visit(Statement statement) {
            aliasResolver = new SchemaAwareTableAliasResolver(statement, columnTableResolver);
            statement.accept(this);
        }

        @Override
        public <S> Void visit(Select select, S context) {
            cteNames.addAll(WithItems.namesOf(select));
            for (Select cte : WithItems.bodiesOf(select)) {
                visit(cte, context);
            }
            TableAliasResolver previousAliasResolver = aliasResolver;
            aliasResolver = new SchemaAwareTableAliasResolver(select, columnTableResolver);
            select.accept(selectVisitor, context);
            aliasResolver = previousAliasResolver;
            return null;
        }


        @Override
        public <S> Void visit(Delete delete, S context) {
            selectVisitor.visitWhere(delete.getWhere());
            return null;
        }

        @Override
        public <S> Void visit(Update update, S context) {
            selectVisitor.visitWhere(update.getWhere());
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
            if (upsert.getSelect() != null) {
                visit(upsert.getSelect(), context);
            }
            return null;
        }

        /**
         * Visits select bodies. Joins are delegated to {@link WhereFromItemVisitor};
         * subqueries appearing as expressions are routed back here via
         * {@link ExpressionVisitorAdapter#setSelectVisitor}.
         */
        private class WhereSelectVisitor extends SelectVisitorAdapter<Void> {
            private final WhereFromItemVisitor fromItemVisitor = new WhereFromItemVisitor();

            @Override
            public <S> Void visit(PlainSelect plainSelect, S context) {
                if (plainSelect.getWhere() != null) {
                    plainSelect.getWhere().accept(new WhereExpressionVisitor());
                }
                if (plainSelect.getJoins() != null) {
                    for (Join join : plainSelect.getJoins()) {
                        join.getRightItem().accept(fromItemVisitor);
                    }
                }
                return null;
            }

            private void visitWhere(Expression where) {
                if (where != null) {
                    where.accept(new WhereExpressionVisitor());
                }
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
                throw new UnsupportedSqlException("with item not supported");
            }

            /**
             * A subquery's scope: its own aliases, falling back to the
             * enclosing query's. The parent is what lets a correlated
             * subquery's reference to an outer alias resolve; without it the
             * outer alias resolved to the subquery's only table — recording a
             * join of that table to itself — or, with two tables, to nothing
             * (sqlx-lww). Every subquery reaches this, whether it appears as an
             * expression (EXISTS, IN, a scalar subquery) or as a derived table.
             */
            @Override
            public <S> Void visit(ParenthesedSelect parenthesedSelect, S context) {
                TableAliasResolver previous = aliasResolver;
                aliasResolver = new SchemaAwareTableAliasResolver(previous, parenthesedSelect, columnTableResolver);
                parenthesedSelect.getSelect().accept(this, context);
                aliasResolver = previous;
                return null;
            }

            private class WhereFromItemVisitor extends FromItemVisitorAdapter<Void> {
                @Override
                public <S> Void visit(ParenthesedSelect parenthesedSelect, S context) {
                    return WhereSelectVisitor.this.visit(parenthesedSelect, context);
                }

                @Override
                public <S> Void visit(LateralSubSelect lateralSubSelect, S context) {
                    throw new UnsupportedSqlException("lateral subselect not supported");
                }
            }

            private class WhereExpressionVisitor extends ExpressionVisitorAdapter<Void> {

                /** The comparison the columns being visited sit under; null for none. */
                private String kind;
                /** For a LIKE kind: its pattern's leading wildcard, empty when unknown. */
                private Optional<Boolean> leadingWildcard = Optional.empty();

                private WhereExpressionVisitor() {
                    super();
                    setSelectVisitor(WhereSelectVisitor.this);
                }

                @Override
                public <S> Void visit(Column column, S context) {
                    // skip the constant true, not sure how this gets into valid sql but it
                    // shouldn't have been parsed as a column to column reference
                    if (column.getTable() == null && "TRUE".equals(column.getColumnName())) {
                        return null;
                    }
                    String tableName = aliasResolver.resolveTableName(column);
                    if (WithItems.isCte(cteNames, tableName)) {
                        return null;
                    }

                    ColumnReference reference = ColumnReference.fromComponents(null, tableName, column.getColumnName());
                    builder.criteriaColumn(reference);
                    if (kind != null) {
                        builder.criteriaComparison(CriteriaComparison.newBuilder()
                            .setColumn(reference)
                            .setKind(kind)
                            .setLeadingWildcard(leadingWildcard.orElse(null))
                            .build());
                    }
                    return null;
                }

                /**
                 * Visits {@code expression} with the columns under it compared
                 * as {@code kind}. Nested comparisons replace it for their own
                 * operands; a subquery gets a fresh visitor and starts clean.
                 */
                private void compare(Expression expression, String kind, Optional<Boolean> leadingWildcard) {
                    if (expression == null) {
                        return;
                    }
                    String previousKind = this.kind;
                    Optional<Boolean> previousWildcard = this.leadingWildcard;
                    this.kind = kind;
                    this.leadingWildcard = leadingWildcard;
                    try {
                        expression.accept(this);
                    } finally {
                        this.kind = previousKind;
                        this.leadingWildcard = previousWildcard;
                    }
                }

                private void compareBoth(net.sf.jsqlparser.expression.BinaryExpression expression, String kind) {
                    compare(expression.getLeftExpression(), kind, Optional.empty());
                    compare(expression.getRightExpression(), kind, Optional.empty());
                }

                @Override
                public <S> Void visit(NotEqualsTo notEqualsTo, S context) {
                    compareBoth(notEqualsTo, KIND_INEQUALITY);
                    return null;
                }

                @Override
                public <S> Void visit(GreaterThan greaterThan, S context) {
                    compareBoth(greaterThan, KIND_RANGE);
                    return null;
                }

                @Override
                public <S> Void visit(GreaterThanEquals greaterThanEquals, S context) {
                    compareBoth(greaterThanEquals, KIND_RANGE);
                    return null;
                }

                @Override
                public <S> Void visit(MinorThan minorThan, S context) {
                    compareBoth(minorThan, KIND_RANGE);
                    return null;
                }

                @Override
                public <S> Void visit(MinorThanEquals minorThanEquals, S context) {
                    compareBoth(minorThanEquals, KIND_RANGE);
                    return null;
                }

                @Override
                public <S> Void visit(Between between, S context) {
                    compare(between.getLeftExpression(), KIND_RANGE, Optional.empty());
                    compare(between.getBetweenExpressionStart(), KIND_RANGE, Optional.empty());
                    compare(between.getBetweenExpressionEnd(), KIND_RANGE, Optional.empty());
                    return null;
                }

                @Override
                public <S> Void visit(InExpression in, S context) {
                    String inKind = in.isNot() ? KIND_NOT_IN : KIND_IN;
                    compare(in.getLeftExpression(), inKind, Optional.empty());
                    compare(in.getRightExpression(), inKind, Optional.empty());
                    return null;
                }

                @Override
                public <S> Void visit(IsNullExpression isNull, S context) {
                    compare(isNull.getLeftExpression(), KIND_NULL_CHECK, Optional.empty());
                    return null;
                }

                @Override
                public <S> Void visit(SelectItem<? extends Expression> selectItem, S context) {
                    return super.visit(selectItem, context);
                }

                @Override
                public <S> Void visit(EqualsTo equalsTo, S context) {
                    compareBoth(equalsTo, KIND_EQUALITY);

                    SimpleColumnAliasResolver visitor = new SimpleColumnAliasResolver(aliasResolver);
                    String leftColumn = visitor.columnReference(equalsTo.getLeftExpression());
                    String rightColumn = visitor.columnReference(equalsTo.getRightExpression());

                    if (leftColumn != null && rightColumn != null) {
                        builder.joinPair(leftColumn, rightColumn);
                    }
                    return null;
                }

                @Override
                public <S> Void visit(ExistsExpression expr, S context) {
                    return super.visit(expr, context);
                }

                /**
                 * LIKE stays criteria, qualified by its kind (sqlx-n5j.7): the
                 * matched side is a like / not like comparison carrying the
                 * pattern's leading wildcard where it can be read, and the
                 * pattern side, if it holds a column, is criteria with no
                 * comparison. REGEXP, RLIKE and the rest share the expression
                 * class but not LIKE's index behaviour, so they get no kind.
                 */
                @Override
                public <S> Void visit(LikeExpression like, S context) {
                    if (like.getLikeKeyWord() != LikeExpression.KeyWord.LIKE) {
                        compare(like.getLeftExpression(), null, Optional.empty());
                        compare(like.getRightExpression(), null, Optional.empty());
                        return null;
                    }
                    compare(like.getLeftExpression(), like.isNot() ? KIND_NOT_LIKE : KIND_LIKE,
                        leadingWildcard(like.getRightExpression()));
                    compare(like.getRightExpression(), null, Optional.empty());
                    return null;
                }
            }
        }
    }
}
