package com.github.safetytrick.sqlexplorer.performanceschema;

import com.github.safetytrick.sqlexplorer.cleanup.SqlCleaner;
import org.springframework.stereotype.Component;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;

@Component
public class SqlParser {

    private final SqlCleaner sqlCleaner = new SqlCleaner();

    public CleanedStatement parseSql(String sql) throws JSQLParserException {
        CleanedStatement statement;
        SqlCleaner.CleanedSql cleanSql = sqlCleaner.cleanSql(sql);
        try {
            statement = new CleanedStatement(CCJSqlParserUtil.parse(cleanSql.getSql()), cleanSql.isTruncated(), cleanSql.isModified());
        } catch (JSQLParserException e) {
            throw new JSQLParserException("[[[" + cleanSql.getSql() + "]]]", e);
        }

        return statement;
    }

    public static class CleanedStatement {
        private final Statement statement;
        private final boolean truncated;
        private final boolean modified;

        public CleanedStatement(Statement statement) {
            this(statement, false, false);

        }

        public CleanedStatement(Statement statement, boolean truncated, boolean modified) {
            this.statement = statement;
            this.truncated = truncated;
            this.modified = modified;
        }

        public Statement getStatement() {
            return statement;
        }

        public boolean isTruncated() {
            return truncated;
        }

        public boolean isModified() {
            return modified;
        }
    }

}
