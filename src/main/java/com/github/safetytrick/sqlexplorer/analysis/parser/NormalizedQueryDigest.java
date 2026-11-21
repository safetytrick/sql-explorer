package com.github.safetytrick.sqlexplorer.analysis.parser;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.github.safetytrick.sqlexplorer.analysis.NameNormalizer;
import com.google.common.hash.Hashing;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.statement.Statement;
import org.springframework.stereotype.Component;

@Component
public class NormalizedQueryDigest {

    private final QueryNormalizer queryNormalizer;
    private final SqlParser sqlParser;


    public NormalizedQueryDigest(QueryNormalizer queryNormalizer, SqlParser sqlParser) {
        this.queryNormalizer = queryNormalizer;
        this.sqlParser = sqlParser;
    }

    public record DigestedQuery(String digest, String normalizedSql, List<QueryNormalizer.Repeat> repeats) { }

    public static String schemaScope(String sessionSchema) {
        String normalized = NameNormalizer.normalize(sessionSchema);
        if (normalized == null || normalized.isBlank() || "dual".equals(normalized)) {
            return "";
        }
        return normalized;
    }

    public static String scopedDigest(String sessionSchema, String normalizedForm) {
        String scope = schemaScope(sessionSchema);
        String hash = Hashing.sha256()
            .hashString(scope.length() + ":" + scope + "\n" + normalizedForm, StandardCharsets.UTF_8)
            .toString();
        return versionPrefix() + hash;
    }

    public static String versionPrefix() {
        return "v" + QueryNormalizer.VERSION + ":";
    }

    public static boolean isCurrentVersion(String digest) {
        return digest != null && digest.startsWith(versionPrefix());
    }

    public DigestedQuery digestedQuery(String sql, String sessionSchema) throws JSQLParserException {
        Statement statement = sqlParser.parseSql(sql).statement();

        QueryNormalizer.NormalizedSql normalized = queryNormalizer.normalizeWithRepeats(statement);

        return new DigestedQuery(scopedDigest(sessionSchema, IdentifierCanonicalizer.canonicalize(normalized.sql())),
                                 normalized.sql(), normalized.repeats());
    }

    public DigestedQuery digestedQuery(String sql) throws JSQLParserException {
        return digestedQuery(sql, null);
    }

    public String normalizedDigest(String sql) throws JSQLParserException {
        return digestedQuery(sql).digest();
    }

    public String normalizedDigest(String sql, String sessionSchema) throws JSQLParserException {
        return digestedQuery(sql, sessionSchema).digest();
    }
}
