package com.github.safetytrick.sqlexplorer.analysis.parser;

import java.nio.charset.StandardCharsets;
import java.util.List;

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

    /** The digest plus the normalized serialization it hashed and the repeats collapsed along the way. */
    public record DigestedQuery(String digest, String normalizedSql, List<QueryNormalizer.Repeat> repeats) { }

    public DigestedQuery digestedQuery(String sql) throws JSQLParserException {
        // we need to reparse because the normalization modifies
        // the statement, and we don't want to do that as it could break other parsers
        Statement statement = sqlParser.parseSql(sql).statement();

        QueryNormalizer.NormalizedSql normalized = queryNormalizer.normalizeWithRepeats(statement);

        String digest = Hashing.sha256()
            .hashString(normalized.sql(), StandardCharsets.UTF_8)
            .toString();
        return new DigestedQuery(digest, normalized.sql(), normalized.repeats());
    }

    public String normalizedDigest(String sql) throws JSQLParserException {
        return digestedQuery(sql).digest();
    }
}
