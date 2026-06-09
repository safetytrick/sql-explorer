package com.github.safetytrick.sqlexplorer.analysis.parser;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import org.springframework.stereotype.Component;

@Component
public class SqlParser {

    private final SqlCleaner sqlCleaner = new SqlCleaner();

    public CleanedStatement parseSql(String sql) throws JSQLParserException {
        CleanedStatement statement;
        SqlCleaner.CleanedSql cleanSql = sqlCleaner.cleanSql(sql);
        try {
            statement = new CleanedStatement(parseStatement(cleanSql.sql()),
                cleanSql.truncated(),
                cleanSql.modified());
        } catch (JSQLParserException e) {
            throw new JSQLParserException("[[[" + cleanSql.sql() + "]]]", e);
        }

        return statement;
    }

    private Statement parseStatement(String sql) throws JSQLParserException {
        return CCJSqlParserUtil.parse(sql);
    }

    public record CleanedStatement(
        Statement statement,
        boolean truncated,
        boolean modified
    ) { }

}
