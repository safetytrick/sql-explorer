package com.github.safetytrick.sqlexplorer.analysis.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.StatementVisitorAdapter;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SelectVisitorAdapter;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.update.Update;
import org.springframework.stereotype.Component;

/**
 * Normalizes a parsed statement so structurally-equivalent query patterns
 * serialize identically (the serialized form feeds
 * {@link NormalizedQueryDigest} and, formatted, becomes the stored display
 * text):
 *
 * <ul>
 *   <li>select-item aliases are stripped (Hibernate's {@code AS id92_0_}
 *       suffixes vary per entity revision);</li>
 *   <li>repeated blocks collapse — duplicate predicates in AND/OR chains,
 *       duplicate CASE WHEN arms, duplicate IN-list elements, and duplicate
 *       batched-INSERT VALUES rows all reduce to one occurrence, so a
 *       10-condition variant of a query hashes like its 12-condition
 *       sibling.</li>
 * </ul>
 *
 * Collapsing is by exact serialized form of the (recursively normalized)
 * sibling, so distinct literals stay distinct. Function argument lists are
 * never collapsed — arity is semantic there. Each collapse is reported as a
 * {@link Repeat} (kept snippet + occurrence count in the sample), in
 * serialization order, so callers can locate the surviving instance in the
 * output (see {@link RepeatLocator}). The pass mutates the statement, which
 * is why {@code NormalizedQueryDigest} reparses rather than sharing the
 * analysis AST.
 */
@Component
public class QueryNormalizer {

    /**
     * The normalization scheme's version. <b>Bump this on any change that
     * alters the serialized output</b>, because the serialized output is what
     * {@link NormalizedQueryDigest} hashes — so any such change re-keys part of
     * the corpus, and a digest produced before it is not comparable with one
     * produced after.
     *
     * <table>
     *   <caption>What each version does</caption>
     *   <tr><th>v1</th><td>select-item alias stripping</td></tr>
     *   <tr><th>v2</th><td>+ repeat collapsing (duplicate predicates, CASE arms,
     *       IN-list elements, batched VALUES rows) and repeat metadata</td></tr>
     *   <tr><th>v3</th><td>+ identifier rendering canonicalized into the hash —
     *       backticks dropped and case folded, so MySQL's {@code `id`} and a
     *       hand-written {@code id} are one query rather than two
     *       ({@code sqlx-65t})</td></tr>
     * </table>
     *
     * <p>Digests carry this as a {@code v<N>:} prefix, which makes them
     * self-describing and makes an accidental cross-scheme comparison
     * impossible — the strings simply differ.
     */
    public static final int VERSION = 3;

    /** A collapsed block: the kept snippet's serialized form and how often it occurred. */
    public record Repeat(String snippet, int count) { }

    /** The normalized serialization plus every collapse that happened, in order. */
    public record NormalizedSql(String sql, List<Repeat> repeats) { }

    public String normalize(Statement sql) {
        return normalizeWithRepeats(sql).sql();
    }

    public NormalizedSql normalizeWithRepeats(Statement sql) {
        List<Repeat> repeats = new ArrayList<>();
        sql.accept(new NormalizerVisitor(repeats));
        return new NormalizedSql(sql.toString(), List.copyOf(repeats));
    }

    private static class NormalizerVisitor extends StatementVisitorAdapter<Void> {

        private final List<Repeat> repeats;
        private final SelectNormalizer selectNormalizer;

        private NormalizerVisitor(List<Repeat> repeats) {
            this.repeats = repeats;
            this.selectNormalizer = new SelectNormalizer(repeats);
        }

        @Override
        public <S> Void visit(Statements stmts, S context) {
            for (Statement statement : stmts) {
                statement.accept(this);
            }
            return null;
        }

        @Override
        public <S> Void visit(Select select, S context) {
            select.accept(selectNormalizer, context);
            return null;
        }

        @Override
        public <S> Void visit(Insert insert, S context) {
            if (insert.getSelect() instanceof Values values) {
                collapseValuesRows(values, repeats);
            } else if (insert.getSelect() != null) {
                insert.getSelect().accept(selectNormalizer, context);
            }
            return null;
        }

        @Override
        public <S> Void visit(Update update, S context) {
            update.setWhere(collapse(update.getWhere(), repeats));
            return null;
        }

        @Override
        public <S> Void visit(Delete delete, S context) {
            delete.setWhere(collapse(delete.getWhere(), repeats));
            return null;
        }
    }

    private static class SelectNormalizer extends SelectVisitorAdapter<Void> {

        private final List<Repeat> repeats;

        private SelectNormalizer(List<Repeat> repeats) {
            this.repeats = repeats;
        }

        // children are visited in serialization order (select items, from,
        // joins, where, having) so collected repeats line up with the text
        @Override
        public <S> Void visit(PlainSelect plainSelect, S context) {
            if (plainSelect.getSelectItems() != null) {
                for (SelectItem<?> item : plainSelect.getSelectItems()) {
                    item.setAlias(null);
                    replaceExpression(item);
                }
            }
            if (plainSelect.getFromItem() instanceof ParenthesedSelect subSelect) {
                subSelect.getSelect().accept(this, context);
            }
            if (plainSelect.getJoins() != null) {
                for (Join join : plainSelect.getJoins()) {
                    if (join.getRightItem() instanceof ParenthesedSelect subSelect) {
                        subSelect.getSelect().accept(this, context);
                    }
                    if (join.getOnExpressions() != null) {
                        List<Expression> collapsed = new ArrayList<>();
                        for (Expression on : join.getOnExpressions()) {
                            collapsed.add(collapse(on, repeats));
                        }
                        join.setOnExpressions(collapsed);
                    }
                }
            }
            plainSelect.setWhere(collapse(plainSelect.getWhere(), repeats));
            plainSelect.setHaving(collapse(plainSelect.getHaving(), repeats));
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

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void replaceExpression(SelectItem item) {
            if (item.getExpression() != null) {
                item.setExpression(collapse(item.getExpression(), repeats));
            }
        }
    }

    /** Recursively collapse repeated blocks inside an expression tree. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Expression collapse(Expression expression, List<Repeat> repeats) {
        if (expression == null) {
            return null;
        }

        if (expression instanceof AndExpression || expression instanceof OrExpression) {
            boolean and = expression instanceof AndExpression;
            List<Expression> flattened = new ArrayList<>();
            flatten(expression, and, flattened);
            Map<String, Expression> distinct = new LinkedHashMap<>();
            Map<String, Integer> occurrences = new LinkedHashMap<>();
            for (Expression leaf : flattened) {
                Expression collapsed = collapse(leaf, repeats);
                String key = collapsed.toString();
                distinct.putIfAbsent(key, collapsed);
                occurrences.merge(key, 1, Integer::sum);
            }
            recordRepeats(occurrences, repeats);
            return rebuild(new ArrayList<>(distinct.values()), and);
        }

        if (expression instanceof CaseExpression caseExpression) {
            caseExpression.setSwitchExpression(collapse(caseExpression.getSwitchExpression(), repeats));
            if (caseExpression.getWhenClauses() != null) {
                Map<String, WhenClause> distinct = new LinkedHashMap<>();
                Map<String, Integer> occurrences = new LinkedHashMap<>();
                for (WhenClause when : caseExpression.getWhenClauses()) {
                    when.setWhenExpression(collapse(when.getWhenExpression(), repeats));
                    when.setThenExpression(collapse(when.getThenExpression(), repeats));
                    String key = when.toString();
                    distinct.putIfAbsent(key, when);
                    occurrences.merge(key, 1, Integer::sum);
                }
                recordRepeats(occurrences, repeats);
                caseExpression.setWhenClauses(new ArrayList<>(distinct.values()));
            }
            caseExpression.setElseExpression(collapse(caseExpression.getElseExpression(), repeats));
            return caseExpression;
        }

        if (expression instanceof InExpression inExpression) {
            inExpression.setLeftExpression(collapse(inExpression.getLeftExpression(), repeats));
            if (inExpression.getRightExpression() instanceof ExpressionList<?> list) {
                dedupInPlace((ExpressionList) list, repeats);
            } else if (inExpression.getRightExpression() != null) {
                inExpression.setRightExpression(collapse(inExpression.getRightExpression(), repeats));
            }
            return inExpression;
        }

        // grouped conditions: (a OR a) parses as an expression list wrapper
        if (expression instanceof ExpressionList<?> list) {
            ExpressionList raw = (ExpressionList) list;
            for (int i = 0; i < raw.size(); i++) {
                raw.set(i, collapse((Expression) raw.get(i), repeats));
            }
            return expression;
        }

        if (expression instanceof ParenthesedSelect subSelect) {
            subSelect.getSelect().accept(new SelectNormalizer(repeats), null);
            return expression;
        }

        return expression;
    }

    private static void recordRepeats(Map<String, Integer> occurrences, List<Repeat> repeats) {
        for (Map.Entry<String, Integer> entry : occurrences.entrySet()) {
            if (entry.getValue() > 1) {
                repeats.add(new Repeat(entry.getKey(), entry.getValue()));
            }
        }
    }

    private static void flatten(Expression expression, boolean and, List<Expression> out) {
        if (and && expression instanceof AndExpression andExpression) {
            flatten(andExpression.getLeftExpression(), true, out);
            flatten(andExpression.getRightExpression(), true, out);
        } else if (!and && expression instanceof OrExpression orExpression) {
            flatten(orExpression.getLeftExpression(), false, out);
            flatten(orExpression.getRightExpression(), false, out);
        } else {
            out.add(expression);
        }
    }

    private static Expression rebuild(List<Expression> leaves, boolean and) {
        Expression result = leaves.getFirst();
        for (int i = 1; i < leaves.size(); i++) {
            result = and ? new AndExpression(result, leaves.get(i)) : new OrExpression(result, leaves.get(i));
        }
        return result;
    }

    /** Dedup IN-list elements by their normalized serialized form: {@code IN (?, ?, ?)} → {@code IN (?)}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void dedupInPlace(ExpressionList list, List<Repeat> repeats) {
        Map<String, Expression> distinct = new LinkedHashMap<>();
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (Object element : list) {
            Expression collapsed = collapse((Expression) element, repeats);
            String key = collapsed.toString();
            distinct.putIfAbsent(key, collapsed);
            occurrences.merge(key, 1, Integer::sum);
        }
        if (distinct.size() < list.size()) {
            recordRepeats(occurrences, repeats);
            list.clear();
            list.addAll(distinct.values());
        }
    }

    /**
     * Batched inserts: {@code VALUES (?, ?), (?, ?)} rows that serialize
     * identically collapse to one row. Rows are the elements when every
     * element is itself an expression list; a single-row VALUES has scalar
     * elements and is left alone.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void collapseValuesRows(Values values, List<Repeat> repeats) {
        ExpressionList expressions = values.getExpressions();
        if (expressions == null || expressions.size() < 2) {
            return;
        }
        boolean allRows = true;
        for (Object element : expressions) {
            if (!(element instanceof ExpressionList)) {
                allRows = false;
                break;
            }
        }
        if (allRows) {
            dedupInPlace(expressions, repeats);
        }
    }
}
