package com.github.safetytrick.sqlexplorer.analysis.parser;

import java.nio.charset.StandardCharsets;

import com.google.common.hash.Hashing;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.statement.Statement;
import org.springframework.stereotype.Component;

@Component
public class NormalizedQueryDigest {

    private final QueryNormalizer queryNormalizer;
    private final SqlParser sqlParser;


    NormalizedQueryDigest(QueryNormalizer queryNormalizer, SqlParser sqlParser) {
        this.queryNormalizer = queryNormalizer;
        this.sqlParser = sqlParser;
    }

    public String normalizedDigest(String sql) throws JSQLParserException {
        // we need to reparse because the normalization modifies
        // the statement, and we don't want to do that as it could break other parsers
        Statement statement = sqlParser.parseSql(sql).statement();

        String normalized = queryNormalizer.normalize(statement);

        return Hashing.sha256()
            .hashString(normalized, StandardCharsets.UTF_8)
            .toString();
    }
}
