package com.github.safetytrick.sqlexplorer.analysis.parser;

final class IdentifierCanonicalizer {

    private IdentifierCanonicalizer() {
    }

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
                i++;
            } else {
                out.append(Character.toLowerCase(c));
                i++;
            }
        }
        return out.toString();
    }

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
