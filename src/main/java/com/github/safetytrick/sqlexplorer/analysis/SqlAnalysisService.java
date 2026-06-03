package com.github.safetytrick.sqlexplorer.analysis;

import java.util.Collections;
import java.util.List;

import com.github.safetytrick.sqlexplorer.analysis.parser.SqlParser;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import jakarta.inject.Inject;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.statement.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SqlAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(SqlAnalysisService.class);


    private final List<SqlAnalyzer> analyzers;
    private final SqlParser sqlParser;

    @Inject
    public SqlAnalysisService(List<SqlAnalyzer> analyzers,
                              SqlParser sqlParser) {
        this.analyzers = analyzers;
        this.sqlParser = sqlParser;
    }
    public SqlAnalysisService(SqlAnalyzer analyzer) {
        this(Collections.singletonList(analyzer), new SqlParser());
    }

    public void analyzeSql(String sql, SqlProfile.Builder builder) throws JSQLParserException {

        SqlParser.CleanedStatement cleanedStatement = sqlParser.parseSql(sql);
        builder.setTruncated(cleanedStatement.truncated());
        builder.setModifiedForAnalysis(cleanedStatement.modified());
        Statement statement = cleanedStatement.statement();

        for (SqlAnalyzer analyzer : analyzers) {
            try {
                analyzer.analyze(statement, builder);
            } catch (Exception e) {
                builder.setAnalysisFailureReason("Failure on: " + analyzer.getClass().getSimpleName() + " message: " + getRootCauseMessage(e));
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

}
