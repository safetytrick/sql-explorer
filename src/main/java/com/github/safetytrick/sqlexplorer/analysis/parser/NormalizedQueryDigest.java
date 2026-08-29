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

    /** The digest plus the normalized serialization it hashed and the repeats collapsed along the way. */
    public record DigestedQuery(String digest, String normalizedSql, List<QueryNormalizer.Repeat> repeats) { }

    /**
     * The canonical form of a session schema for digest purposes.
     *
     * <p>One value has to stand for "this statement ran with no default
     * database", and the ingest pipeline spells that two ways: the raw row
     * carries null, and {@code SqlProfile.schemaName} carries the {@code
     * "dual"} placeholder it is defaulted to. Both fold to the same empty
     * scope here, so a schema-less statement digests identically whichever
     * call site reaches the hash first — the alternative is one statement
     * appearing as two profiles depending on where it was scoped.
     *
     * <p>Normalized (backticks stripped, lower-cased) for the same reason
     * schema names are compared that way everywhere else: {@code DEB} and
     * {@code app_main} are one schema, not two.
     */
    public static String schemaScope(String sessionSchema) {
        String normalized = NameNormalizer.normalize(sessionSchema);
        if (normalized == null || normalized.isBlank() || "dual".equals(normalized)) {
            return "";
        }
        return normalized;
    }

    /**
     * Hash a normalized form within its session schema.
     *
     * <p>Length-prefixed rather than delimited. A separator alone is only
     * unambiguous if it cannot occur in the scope, and a backtick-quoted
     * MySQL identifier can contain very nearly anything — including the
     * newline this first used, which made {@code ("a", "b\nc")} and
     * {@code ("a\nb", "c")} hash identically. Prefixing the length makes the
     * encoding injective for any scope at all.
     */
    public static String scopedDigest(String sessionSchema, String normalizedForm) {
        String scope = schemaScope(sessionSchema);
        return Hashing.sha256()
            .hashString(scope.length() + ":" + scope + "\n" + normalizedForm, StandardCharsets.UTF_8)
            .toString();
    }

    /**
     * Digest {@code sql} as it was run in {@code sessionSchema}.
     *
     * <p>The schema is part of the hash because the digest is what decides
     * which rows are one profile, and an unqualified statement is not one
     * statement across schemas. Framework SQL makes this the common case, not
     * an edge: {@code SELECT COUNT(*) FROM `migrations` WHERE `created_at` IS
     * NULL} ran in ten schemas on the regression server and collapsed into a
     * single profile that reported one of them — chosen by whichever row won
     * a race, and observably different between two imports.
     *
     * <p>Note this scopes by the *session* schema, not by the tables the
     * statement turns out to reference. A fully-qualified cross-schema join
     * therefore still gets one profile per session it ran from, which is
     * correct: those are different rows in the performance schema and their
     * metrics are not interchangeable.
     */
    public DigestedQuery digestedQuery(String sql, String sessionSchema) throws JSQLParserException {
        // we need to reparse because the normalization modifies
        // the statement, and we don't want to do that as it could break other parsers
        Statement statement = sqlParser.parseSql(sql).statement();

        QueryNormalizer.NormalizedSql normalized = queryNormalizer.normalizeWithRepeats(statement);

        // The hash sees identifiers canonicalized; the display text does not.
        // MySQL backticks every identifier and a human types none, so without
        // this the same statement written two ways is two profiles — and a query
        // example, which is written by a human for a profile that came from
        // MySQL, could never match the group it is an example of.
        return new DigestedQuery(scopedDigest(sessionSchema, IdentifierCanonicalizer.canonicalize(normalized.sql())),
                                 normalized.sql(), normalized.repeats());
    }

    /** Digest with no session schema — the unscoped form. */
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
