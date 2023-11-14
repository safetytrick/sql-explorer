package com.github.safetytrick.sqlexplorer.analysis;

import java.nio.charset.StandardCharsets;

import com.github.safetytrick.sqlexplorer.cleanup.QueryNormalizer;
import com.github.safetytrick.sqlexplorer.performanceschema.SqlParser;
import org.springframework.stereotype.Component;

import com.google.common.hash.Hashing;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.statement.Statement;

@Component
public class NormalizedQueryAnalyzer {

    private final QueryNormalizer queryNormalizer;
    private final SqlParser sqlParser;


    public NormalizedQueryAnalyzer(QueryNormalizer queryNormalizer, SqlParser sqlParser) {
        this.queryNormalizer = queryNormalizer;
        this.sqlParser = sqlParser;
    }

    public String normalizedDigest(String sql) throws JSQLParserException {
        // we need to re-parse because the normalization modifies
        // the statement and we don't want to do that as it could break other parsers
        Statement statement = sqlParser.parseSql(sql).getStatement();

        String normalized = queryNormalizer.normalize(statement);

        return Hashing.sha256()
            .hashString(normalized, StandardCharsets.UTF_8)
            .toString();
    }
}
