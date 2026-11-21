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

@Component
public class QueryParameterizer {

    public String parameterize(String sql) throws net.sf.jsqlparser.JSQLParserException {
        Statement statement = CCJSqlParserUtil.parse(sql);
        return parameterize(statement);
    }

    public String parameterize(Statement statement) {
        StringBuilder out = new StringBuilder();
        SelectDeParser selects = new SelectDeParser();
        ParameterizingExpressionDeParser expressions = new ParameterizingExpressionDeParser(selects, out);
        selects.setExpressionVisitor(expressions);
        selects.setBuilder(out);
        statement.accept(new StatementDeParser(expressions, selects, out), null);
        return out.toString();
    }

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

        @Override
        public <S> StringBuilder visit(JdbcParameter jdbcParameter, S context) {
            return parameter();
        }

        private StringBuilder parameter() {
            return getBuilder().append("?");
        }
    }
}
