package com.github.safetytrick.sqlexplorer.analysis.parser;

/**
 * Canonicalizes identifier <em>rendering</em> in a serialized statement, so that
 * how a name is written stops affecting what the statement is.
 *
 * <p>MySQL's digest text backticks every identifier it can — {@code SELECT `id`
 * FROM `orders` WHERE `total` > ?} — and a person writing the same statement by
 * hand types {@code select id from orders where total > ?}. jsqlparser preserves
 * both backticks and case through parse-and-serialize, so those two produce
 * different digests today and land in different merge groups. They are the same
 * query. That is a defect in the identity, not a fact about the queries.
 *
 * <p>It bites hardest on query examples ({@code sqlx-hlu}): an example is
 * written by a human and the profile it belongs to came from MySQL, so the two
 * renderings are guaranteed to differ and the example would key to a digest no
 * profile carries.
 *
 * <h2>What this deliberately is not</h2>
 *
 * <p>This runs over the <em>serialized</em> form on its way into the hash, not
 * over the AST, and the display text is left exactly as it was. The normalized
 * SQL is what the UI renders; lower-casing that would make every query page
 * unreadable to fix a hashing problem. Identity and presentation are different
 * questions and this only answers the first.
 *
 * <p>String literals are copied through untouched. A backtick or an upper-case
 * letter inside {@code '...'} is data, and rewriting it would merge statements
 * that differ in a value — the exact opposite of what normalization is for.
 */
final class IdentifierCanonicalizer {

    private IdentifierCanonicalizer() {
    }

    /**
     * Lower-case everything outside string literals and drop backticks.
     *
     * <p>Lower-casing keywords as well as identifiers is harmless and cheaper
     * than telling them apart: SQL keywords are case-insensitive, the serializer
     * emits them in a fixed case anyway, and this string is only ever hashed.
     */
    static String canonicalize(String sql) {
        if (sql == null) {
            return null;
        }

        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                i = copyQuoted(sql, i, c, out);
            } else if (c == '`') {
                // an identifier's quoting is rendering, not identity
                i++;
            } else {
                out.append(Character.toLowerCase(c));
                i++;
            }
        }
        return out.toString();
    }

    /**
     * Copy a quoted string verbatim, including its delimiters, and return the
     * index just past it. Handles both escape conventions MySQL accepts: a
     * doubled delimiter ({@code 'it''s'}) and a backslash ({@code 'it\'s'}). An
     * unterminated literal copies to end of input rather than throwing — this is
     * a hashing helper, and a statement that got this far already parsed.
     */
    private static int copyQuoted(String sql, int start, char quote, StringBuilder out) {
        out.append(sql.charAt(start));
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\\' && i + 1 < sql.length()) {
                out.append(c).append(sql.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    out.append(c).append(c);
                    i += 2;
                    continue;
                }
                out.append(c);
                return i + 1;
            }
            out.append(c);
            i++;
        }
        return i;
    }
}
