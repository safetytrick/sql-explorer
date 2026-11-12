package com.github.safetytrick.sqlexplorer.analysis;

import java.util.Locale;
import java.util.Set;
import java.util.StringTokenizer;

/**
 * A statement's type read from its text, before and without parsing: the
 * operation it performs — SELECT, INSERT, UPDATE, DELETE, REPLACE, or the
 * first keyword of anything else (SET, COMMIT, CREATE, …).
 *
 * <p>Two openings do not name the operation. {@code WITH} only says the
 * statement has CTEs, which can feed a SELECT, an UPDATE or a DELETE; and
 * {@code (} opens a parenthesised SELECT. For those the text is scanned past
 * the CTE list, tracking nothing but parenthesis depth and quoting, to the
 * keyword that follows it. Nothing inside a CTE body is interpreted, which is
 * why this still works on statements the parser rejects — parse failures are
 * almost always inside a body.
 *
 * <p>When even that cannot find the operation the type is {@link #UNKNOWN}:
 * usually because Performance Schema truncated the text before reaching it
 * (its digest text stops at 1024 bytes by default, and long CTEs are what
 * reach that). The statement's {@code truncated} and {@code analysisFailed}
 * flags say why.
 *
 * <p>A statement that does parse is typed from the parsed statement instead
 * ({@link StatementTypeAnalyzer}); this is what it starts from, and what a
 * statement keeps when it is not parsed.
 */
public final class StatementTypes {

    /** The type of a statement whose operation could not be determined. */
    public static final String UNKNOWN = "UNKNOWN";

    /** What can follow a CTE list in MySQL. INSERT and REPLACE put theirs after the target. */
    private static final Set<String> AFTER_CTES = Set.of("SELECT", "UPDATE", "DELETE");

    private StatementTypes() {
    }

    public static String fromText(String sql) {
        String first = firstWord(sql);
        if (first == null) {
            return UNKNOWN;
        }
        if (!first.equals("WITH") && !first.startsWith("(")) {
            return first;
        }
        String operation = scan(new Lexer(sql));
        return operation == null ? UNKNOWN : operation;
    }

    /** The first whitespace-delimited token, upper-cased: the type as it was before CTEs were read. */
    static String firstWord(String sql) {
        if (sql == null) {
            return null;
        }
        StringTokenizer tokenizer = new StringTokenizer(sql);
        return tokenizer.hasMoreTokens() ? tokenizer.nextToken().toUpperCase(Locale.ROOT) : null;
    }

    private static String scan(Lexer lexer) {
        Token token = lexer.next();
        boolean parenthesised = false;
        while (token.kind == Kind.OPEN) {
            parenthesised = true;
            token = lexer.next();
        }
        if (token.kind != Kind.WORD) {
            return null;
        }
        String word = token.upper();
        if (word.equals("SELECT")) {
            return "SELECT";
        }
        if (word.equals("WITH")) {
            String operation = afterCtes(lexer);
            // a parenthesised statement is a query expression: only a SELECT fits
            return parenthesised && operation != null && !operation.equals("SELECT") ? null : operation;
        }
        return null;
    }

    /** WITH [RECURSIVE] name [(columns)] AS (body) [, …] then the operation. */
    private static String afterCtes(Lexer lexer) {
        Token token = lexer.next();
        if (token.isWord("RECURSIVE")) {
            token = lexer.next();
        }
        while (true) {
            if (token.kind != Kind.WORD && token.kind != Kind.QUOTED) {
                return null;
            }
            token = lexer.next();
            if (token.kind == Kind.OPEN) {
                if (!lexer.skipGroup()) {
                    return null;
                }
                token = lexer.next();
            }
            if (!token.isWord("AS")) {
                return null;
            }
            if (lexer.next().kind != Kind.OPEN || !lexer.skipGroup()) {
                return null;
            }
            token = lexer.next();
            if (token.kind == Kind.COMMA) {
                token = lexer.next();
                continue;
            }
            if (token.kind == Kind.OPEN) {
                // WITH … (SELECT …): a parenthesised query expression
                token = lexer.next();
                return token.isWord("SELECT") ? "SELECT" : null;
            }
            if (token.kind == Kind.WORD && AFTER_CTES.contains(token.upper())) {
                return token.upper();
            }
            return null;
        }
    }

    private enum Kind { WORD, QUOTED, OPEN, CLOSE, COMMA, OTHER, END }

    private record Token(Kind kind, String text) {
        String upper() {
            return text.toUpperCase(Locale.ROOT);
        }

        boolean isWord(String word) {
            return kind == Kind.WORD && text.equalsIgnoreCase(word);
        }
    }

    /**
     * Just enough of MySQL's lexical rules to find the top level: words,
     * quoted strings and identifiers (so a parenthesis inside one is not
     * counted), comments, and the punctuation the CTE list is made of.
     */
    private static final class Lexer {
        private final String sql;
        private int at;

        Lexer(String sql) {
            this.sql = sql;
        }

        Token next() {
            skipSpaceAndComments();
            if (at >= sql.length()) {
                return new Token(Kind.END, "");
            }
            char c = sql.charAt(at);
            if (c == '(') {
                at++;
                return new Token(Kind.OPEN, "(");
            }
            if (c == ')') {
                at++;
                return new Token(Kind.CLOSE, ")");
            }
            if (c == ',') {
                at++;
                return new Token(Kind.COMMA, ",");
            }
            if (c == '\'' || c == '"' || c == '`') {
                int start = at;
                if (!skipQuoted(c)) {
                    return new Token(Kind.END, "");
                }
                return new Token(Kind.QUOTED, sql.substring(start, at));
            }
            if (isWordChar(c)) {
                int start = at;
                while (at < sql.length() && isWordChar(sql.charAt(at))) {
                    at++;
                }
                return new Token(Kind.WORD, sql.substring(start, at));
            }
            at++;
            return new Token(Kind.OTHER, String.valueOf(c));
        }

        /** Past the group whose ( was just read; false when the text ends inside it. */
        boolean skipGroup() {
            int depth = 1;
            while (depth > 0) {
                Token token = next();
                switch (token.kind) {
                    case OPEN -> depth++;
                    case CLOSE -> depth--;
                    case END -> {
                        return false;
                    }
                    default -> { }
                }
            }
            return true;
        }

        private boolean skipQuoted(char quote) {
            at++;
            while (at < sql.length()) {
                char c = sql.charAt(at);
                if (c == '\\' && quote != '`') {
                    at += 2;
                    continue;
                }
                if (c == quote) {
                    // a doubled quote is an escaped one
                    if (at + 1 < sql.length() && sql.charAt(at + 1) == quote) {
                        at += 2;
                        continue;
                    }
                    at++;
                    return true;
                }
                at++;
            }
            return false;
        }

        private void skipSpaceAndComments() {
            while (at < sql.length()) {
                char c = sql.charAt(at);
                if (Character.isWhitespace(c)) {
                    at++;
                } else if (sql.startsWith("/*", at)) {
                    int end = sql.indexOf("*/", at + 2);
                    at = end < 0 ? sql.length() : end + 2;
                } else if (c == '#' || sql.startsWith("-- ", at)) {
                    int end = sql.indexOf('\n', at);
                    at = end < 0 ? sql.length() : end + 1;
                } else {
                    return;
                }
            }
        }

        private static boolean isWordChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '$';
        }
    }
}
