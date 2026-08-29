package com.github.safetytrick.sqlexplorer.analysis.parser;

import net.sf.jsqlparser.expression.DateValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.HexValue;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.TimeValue;
import net.sf.jsqlparser.expression.TimestampValue;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.util.deparser.SelectDeParser;
import net.sf.jsqlparser.util.deparser.StatementDeParser;
import org.springframework.stereotype.Component;

/**
 * Replaces literal values with {@code ?}, the way MySQL does when it builds a
 * statement digest.
 *
 * <p>Everything the corpus holds has already been through this: Performance
 * Schema stores {@code digest_text}, not the statement as run, so
 * {@code total > 100} reaches us as {@code total > ?}. A query example is the
 * one thing that arrives with its values intact — that is the whole point of it
 * — so it has to be put through the same reduction before its digest can be
 * compared with anything.
 *
 * <h2>Rules, measured rather than recalled</h2>
 *
 * <p>Taken from a real MySQL 8.4.4 by running statements and reading back
 * {@code SQL_TEXT} against {@code DIGEST_TEXT} from
 * {@code performance_schema.events_statements_history}:
 *
 * <ul>
 *   <li>numbers, strings, dates, times, timestamps and hex all become {@code ?};</li>
 *   <li>{@code IS NULL} keeps its {@code NULL} — it is syntax there, not a value —
 *       while {@code = NULL} becomes {@code = ?};</li>
 *   <li>{@code LIMIT 10 OFFSET 5} becomes {@code LIMIT ? OFFSET ?};</li>
 *   <li>a negative literal keeps its sign: {@code > -5} becomes {@code > - ?}.</li>
 * </ul>
 *
 * <p>MySQL renders a multi-element {@code IN} list as {@code IN (...)} and even
 * a single-element one as {@code IN (...)}, where this produces {@code IN (?)}
 * or {@code IN (?, ?, ?)}. That difference does not survive to the digest:
 * {@link QueryNormalizer} collapses duplicate IN-list elements, and jsqlparser
 * reads MySQL's {@code (...)} as a single parameter, so all three converge on
 * {@code IN (?)}. Verified, because it looked like a blocker and is not.
 */
@Component
public class QueryParameterizer {

    /**
     * @return {@code sql} with its literals replaced by {@code ?}
     * @throws net.sf.jsqlparser.JSQLParserException if the statement cannot be parsed
     */
    public String parameterize(String sql) throws net.sf.jsqlparser.JSQLParserException {
        Statement statement = CCJSqlParserUtil.parse(sql);
        return parameterize(statement);
    }

    /** As {@link #parameterize(String)}, for an already-parsed statement. */
    public String parameterize(Statement statement) {
        StringBuilder out = new StringBuilder();
        SelectDeParser selects = new SelectDeParser();
        ParameterizingExpressionDeParser expressions = new ParameterizingExpressionDeParser(selects, out);
        selects.setExpressionVisitor(expressions);
        selects.setBuilder(out);
        statement.accept(new StatementDeParser(expressions, selects, out), null);
        return out.toString();
    }

    /**
     * Writes {@code ?} in place of any literal it is asked to render.
     *
     * <p>Done at deparse time rather than by mutating the AST: the values are
     * only in the way when the statement is being serialized for a digest, and
     * every caller that wants the statement itself still gets it unharmed.
     */
    private static final class ParameterizingExpressionDeParser
        extends net.sf.jsqlparser.util.deparser.ExpressionDeParser {

        private ParameterizingExpressionDeParser(SelectDeParser selectDeParser, StringBuilder buffer) {
            super(selectDeParser, buffer);
        }

        @Override
        public <S> StringBuilder visit(LongValue value, S context) {
            return parameter();
        }

        @Override
        public <S> StringBuilder visit(DoubleValue value, S context) {
            return parameter();
        }

        @Override
        public <S> StringBuilder visit(StringValue value, S context) {
            return parameter();
        }

        @Override
        public <S> StringBuilder visit(DateValue value, S context) {
            return parameter();
        }

        @Override
        public <S> StringBuilder visit(TimeValue value, S context) {
            return parameter();
        }

        @Override
        public <S> StringBuilder visit(TimestampValue value, S context) {
            return parameter();
        }

        @Override
        public <S> StringBuilder visit(HexValue value, S context) {
            return parameter();
        }

        /**
         * A bare {@code NULL} used as a value is a value; the {@code NULL} in
         * {@code IS NULL} is syntax. jsqlparser models the second as
         * {@link IsNullExpression} and never routes it through here, so this
         * only ever sees the first — which is what MySQL parameterizes.
         */
        @Override
        public <S> StringBuilder visit(NullValue value, S context) {
            return parameter();
        }

        /**
         * MySQL keeps the sign as its own token — {@code > -5} digests to
         * {@code > - ?}, not {@code > ?} — so the operator is written and only
         * the magnitude is replaced.
         */
        @Override
        public <S> StringBuilder visit(SignedExpression expression, S context) {
            getBuilder().append(expression.getSign());
            expression.getExpression().accept(this, context);
            return getBuilder();
        }

        /** An existing {@code ?} stays one, so parameterizing twice is a no-op. */
        @Override
        public <S> StringBuilder visit(JdbcParameter jdbcParameter, S context) {
            return parameter();
        }

        private StringBuilder parameter() {
            return getBuilder().append("?");
        }
    }
}
