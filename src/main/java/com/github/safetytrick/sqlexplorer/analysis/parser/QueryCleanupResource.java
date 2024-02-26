package com.github.safetytrick.sqlexplorer.analysis.parser;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import org.hibernate.engine.jdbc.internal.BasicFormatterImpl;
import org.hibernate.engine.jdbc.internal.Formatter;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class QueryCleanupResource {

    private final QueryNormalizer queryNormalizer;
    private final SqlCleaner sqlCleaner;
    private final Formatter sqlFormatter = new BasicFormatterImpl();

    public QueryCleanupResource(QueryNormalizer queryNormalizer, SqlCleaner sqlCleaner) {
        this.queryNormalizer = queryNormalizer;
        this.sqlCleaner = sqlCleaner;
    }

    @PostMapping(path = "/api/sql-explorer/util/clean", consumes = "text/plain", produces = "application/json")
    public SqlCleaner.CleanedSql cleanQuery(@RequestBody String query) {
        return sqlCleaner.cleanSql(query);
    }

    @PostMapping(path ="/api/sql-explorer/util/normalize", consumes = "text/plain", produces = "application/json")
    public NormalizedQuery normalizeQuery(@RequestBody String query, @RequestParam(name="clean", defaultValue = "true") boolean clean){
        Statement statement;
        try {
            if (clean) {
                SqlCleaner.CleanedSql cleanedSql = sqlCleaner.cleanSql(query);
                statement = CCJSqlParserUtil.parse(cleanedSql.sql());
            } else {
                statement = CCJSqlParserUtil.parse(query);
            }
        } catch (JSQLParserException exception) {
            return new NormalizedQuery("/* parse error */\n" + query);
        }

        String normalized = queryNormalizer.normalize(statement);
        return new NormalizedQuery(sqlFormatter.format(normalized));

    }

    record NormalizedQuery(String text) { }

}
