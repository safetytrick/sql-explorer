package com.github.safetytrick.sqlexplorer.analysis;

import java.util.Locale;
import java.util.Set;
import java.util.StringTokenizer;

public final class StatementTypes {

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
            return parenthesised && operation != null && !operation.equals("SELECT") ? null : operation;
        }
        return null;
    }

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
