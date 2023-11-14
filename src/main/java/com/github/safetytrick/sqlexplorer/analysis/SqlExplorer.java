package com.github.safetytrick.sqlexplorer.analysis;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

import com.github.safetytrick.sqlexplorer.core.AnalyzedSql;
import com.github.safetytrick.sqlexplorer.performanceschema.SqlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.statement.Statement;

@Service
public class SqlExplorer {

    private static final Logger log = LoggerFactory.getLogger(SqlExplorer.class);


    private final List<SqlAnalyzer> analyzers;
    private final SqlParser sqlParser;

    public SqlExplorer() {
        this(defaultAnalyzers(), new SqlParser());
    }

    @Inject
    public SqlExplorer(List<SqlAnalyzer> analyzers, SqlParser sqlParser) {
        this.analyzers = analyzers;
        this.sqlParser = sqlParser;
    }


    public SqlExplorer(SqlAnalyzer analyzer) {
        this(Collections.singletonList(analyzer), new SqlParser());
    }

    public void analyzeSql(String sql, AnalyzedSql.Builder builder) throws JSQLParserException {

        SqlParser.CleanedStatement cleanedStatement = sqlParser.parseSql(sql);
        builder.truncated(cleanedStatement.isTruncated());
        builder.modifiedForAnalysis(cleanedStatement.isModified());
        Statement statement = cleanedStatement.getStatement();

        for (SqlAnalyzer analyzer : analyzers) {
            try {
                analyzer.analyze(statement, builder);
            } catch (Exception e) {
                builder.failureReason("Failure on: " + analyzer.getClass().getSimpleName() + " message: " + getRootCauseMessage(e));
                log.debug("Failure on: {}", analyzer.getClass().getSimpleName(), e);
            }

        }
    }

    private String getRootCauseMessage(Throwable e) {
        String message = e.toString();
        while (e != null) {
            if (e.getMessage() != null) {
                message = e.getMessage();
            }
            e = e.getCause();
        }
        return message;
    }

    private static List<SqlAnalyzer> defaultAnalyzers() {
        return Arrays.asList(
          new TableNameAnalyzer(),
          new SchemaAnalyzer(),
          new JoinColumnAnalyzer(),
          new WhereClauseAnalyzer(),
          new GeneratedSqlAnalyzer()
        );
    }

}
