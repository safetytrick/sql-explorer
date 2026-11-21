package com.github.safetytrick.sqlexplorer.ddl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hand-written rather than jsqlparser: jsqlparser rejects common MySQL forms
 * outright ({@code CHANGE name full_name …} fails on the keyword {@code name}).
 */
public final class AlterParser {

    private AlterParser() { }

    public enum Kind {
        ADD_COLUMN, DROP_COLUMN, MODIFY_COLUMN, CHANGE_COLUMN, RENAME_COLUMN, COLUMN_DEFAULT, COLUMN_VISIBILITY,
        ADD_INDEX, DROP_INDEX, RENAME_INDEX, INDEX_VISIBILITY, ADD_PRIMARY_KEY, DROP_PRIMARY_KEY,
        ADD_FOREIGN_KEY, DROP_FOREIGN_KEY, DROP_CONSTRAINT,
        CONVERT_CHARSET, TABLE_DEFAULT_CHARSET, REBUILD, ROW_FORMAT, AUTO_INCREMENT, COMMENT, RENAME_TABLE,
        ALGORITHM, LOCK, UNKNOWN
    }

    public record ColumnDef(String name, String type, Integer length, Integer scale, String charset,
                            String collation, Boolean nullable, boolean hasDefault, boolean generated,
                            boolean stored) {

        public ColumnDef withCharset(String newCharset, String newCollation) {
            return new ColumnDef(name, type, length, scale, newCharset, newCollation, nullable, hasDefault,
                generated, stored);
        }

        public ColumnDef renamed(String newName) {
            return new ColumnDef(newName, type, length, scale, charset, collation, nullable, hasDefault,
                generated, stored);
        }
    }

    public record IndexPart(String column, Integer prefix, boolean functional) { }

    public record IndexDef(String name, boolean unique, boolean fulltext, boolean spatial, boolean primary,
                           List<IndexPart> parts) { }

    public record Clause(Kind kind, String text, String name, String newName, ColumnDef column, IndexDef index,
                         String value, String collation, String referencedTable) {

        static Clause of(Kind kind, String text) {
            return new Clause(kind, text, null, null, null, null, null, null, null);
        }
    }

    public record ParsedAlter(String schema, String table, List<Clause> clauses, String error,
                              List<String> syntaxErrors) {

        public ParsedAlter(String schema, String table, List<Clause> clauses, String error) {
            this(schema, table, clauses, error, List.of());
        }

        public String requested(Kind kind) {
            return clauses.stream().filter(c -> c.kind() == kind).map(Clause::value).findFirst().orElse(null);
        }
    }

    private static final Pattern ALTER = Pattern.compile(
        "^\\s*ALTER\\s+(?:ONLINE\\s+|IGNORE\\s+)*TABLE\\s+(" + Ident.QUALIFIED + ")\\s*(.*)$",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CREATE_INDEX = Pattern.compile(
        "^\\s*CREATE\\s+(UNIQUE\\s+|FULLTEXT\\s+|SPATIAL\\s+)?INDEX\\s+(" + Ident.ONE + ")\\s+(?:USING\\s+\\w+\\s+)?"
            + "ON\\s+(" + Ident.QUALIFIED + ")\\s*(\\(.*\\))(.*)$",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DROP_INDEX = Pattern.compile(
        "^\\s*DROP\\s+INDEX\\s+(" + Ident.ONE + ")\\s+ON\\s+(" + Ident.QUALIFIED + ")(.*)$",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public static ParsedAlter parse(String sql) {
        String statement = stripTerminator(sql == null ? "" : sql.trim());
        Matcher alter = ALTER.matcher(statement);
        if (alter.matches()) {
            String[] table = Ident.split(alter.group(1));
            List<Clause> clauses = new ArrayList<>();
            List<String> syntax = new ArrayList<>();
            for (String text : splitTopLevel(alter.group(2))) {
                if (!text.isBlank()) {
                    for (String piece : missingCommas(text.trim(), syntax)) {
                        Clause clause = clause(piece);
                        optionValue(clause, syntax);
                        clauses.add(clause);
                    }
                }
            }
            return new ParsedAlter(table[0], table[1], clauses, clauses.isEmpty() ? "ALTER TABLE with no changes" : null,
                List.copyOf(syntax));
        }
        Matcher create = CREATE_INDEX.matcher(statement);
        if (create.matches()) {
            String kind = create.group(1) == null ? "" : create.group(1).trim().toUpperCase(Locale.ROOT);
            String[] table = Ident.split(create.group(3));
            IndexDef index = new IndexDef(Ident.unquote(create.group(2)), kind.equals("UNIQUE"),
                kind.equals("FULLTEXT"), kind.equals("SPATIAL"), false, parts(inner(create.group(4))));
            List<Clause> clauses = new ArrayList<>();
            clauses.add(new Clause(Kind.ADD_INDEX, statement, index.name(), null, null, index, null, null, null));
            List<String> syntax = new ArrayList<>();
            options(create.group(5), clauses, syntax);
            return new ParsedAlter(table[0], table[1], clauses, null, List.copyOf(syntax));
        }
        Matcher drop = DROP_INDEX.matcher(statement);
        if (drop.matches()) {
            String[] table = Ident.split(drop.group(2));
            List<Clause> clauses = new ArrayList<>();
            clauses.add(new Clause(Kind.DROP_INDEX, statement, Ident.unquote(drop.group(1)), null, null, null,
                null, null, null));
            List<String> syntax = new ArrayList<>();
            options(drop.group(3), clauses, syntax);
            return new ParsedAlter(table[0], table[1], clauses, null, List.copyOf(syntax));
        }
        return new ParsedAlter(null, null, List.of(),
            "Not an ALTER TABLE, CREATE INDEX or DROP INDEX statement");
    }

    private static void options(String tail, List<Clause> into, List<String> syntax) {
        if (tail.trim().startsWith(",")) {
            syntax.add("CREATE INDEX and DROP INDEX take ALGORITHM= and LOCK= after a space, not a comma: "
                + "MySQL rejects \"" + tail.trim() + "\" (ERROR 1064).");
        }
        Matcher m = Pattern.compile("\\b(ALGORITHM|LOCK)\\s*=?\\s*(\\w+)", Pattern.CASE_INSENSITIVE).matcher(tail);
        while (m.find()) {
            Kind kind = m.group(1).equalsIgnoreCase("ALGORITHM") ? Kind.ALGORITHM : Kind.LOCK;
            Clause clause = new Clause(kind, m.group(0), null, null, null, null,
                m.group(2).toUpperCase(Locale.ROOT), null, null);
            optionValue(clause, syntax);
            into.add(clause);
        }
    }

    static final List<String> ALGORITHMS = List.of("DEFAULT", "INSTANT", "INPLACE", "COPY");
    static final List<String> LOCKS = List.of("DEFAULT", "NONE", "SHARED", "EXCLUSIVE");

    private static final String OPTION = "(?:ALGORITHM\\s*=?\\s*(?:" + String.join("|", ALGORITHMS) + ")"
        + "|LOCK\\s*=?\\s*(?:" + String.join("|", LOCKS) + "))";
    private static final Pattern TRAILING_OPTION = Pattern.compile("^(.*\\S)\\s+(" + OPTION + ")$",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LEADING_OPTION = Pattern.compile("^(" + OPTION + ")\\s+(\\S.*)$",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    static List<String> missingCommas(String text, List<String> syntax) {
        List<String> trailing = new ArrayList<>();
        String rest = text;
        Matcher m;
        while ((m = TRAILING_OPTION.matcher(rest)).matches()) {
            trailing.addFirst(m.group(2));
            rest = m.group(1);
        }
        List<String> leading = new ArrayList<>();
        while ((m = LEADING_OPTION.matcher(rest)).matches()) {
            leading.add(m.group(1));
            rest = m.group(2);
        }
        if (trailing.isEmpty() && leading.isEmpty()) {
            return List.of(text);
        }
        List<String> pieces = new ArrayList<>(leading);
        pieces.add(rest);
        pieces.addAll(trailing);
        syntax.add("Missing comma: MySQL rejects \"" + text + "\" (ERROR 1064). ALGORITHM= and LOCK= are items "
            + "of the ALTER TABLE list like any other change, so write \"" + String.join(", ", pieces) + "\".");
        return pieces;
    }

    private static void optionValue(Clause clause, List<String> syntax) {
        if (clause.kind() == Kind.ALGORITHM && !ALGORITHMS.contains(clause.value())) {
            syntax.add("Unknown algorithm: MySQL rejects ALGORITHM=" + clause.value() + ". It takes "
                + String.join(", ", ALGORITHMS) + ".");
        } else if (clause.kind() == Kind.LOCK && !LOCKS.contains(clause.value())) {
            syntax.add("Unknown lock: MySQL rejects LOCK=" + clause.value() + ". It takes "
                + String.join(", ", LOCKS) + ".");
        }
    }

    private static String stripTerminator(String sql) {
        String s = sql;
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;
    private static final Pattern P_ALGORITHM = Pattern.compile("^ALGORITHM\\s*=?\\s*(\\w+)$", FLAGS);
    private static final Pattern P_LOCK = Pattern.compile("^LOCK\\s*=?\\s*(\\w+)$", FLAGS);
    private static final Pattern P_ADD_PK = Pattern.compile(
        "^ADD\\s+(?:CONSTRAINT\\s+(?:" + Ident.ONE + "\\s+)?)?PRIMARY\\s+KEY\\s*(?:USING\\s+\\w+\\s*)?(\\(.*\\))", FLAGS);
    private static final Pattern P_ADD_FK = Pattern.compile(
        "^ADD\\s+(?:CONSTRAINT\\s+(" + Ident.ONE + ")?\\s*)?FOREIGN\\s+KEY\\s*(?:" + Ident.ONE + "\\s*)?(\\([^)]*\\))"
            + "\\s*REFERENCES\\s+(" + Ident.QUALIFIED + ")", FLAGS);
    private static final Pattern P_ADD_INDEX = Pattern.compile(
        "^ADD\\s+(?:CONSTRAINT\\s+(?:" + Ident.ONE + "\\s+)?)?(UNIQUE|FULLTEXT|SPATIAL)?\\s*(?:INDEX|KEY)?\\s*"
            + "(" + Ident.ONE + ")?\\s*(?:USING\\s+\\w+\\s*)?(\\(.*\\))", FLAGS);
    private static final Pattern P_ADD_COLUMN = Pattern.compile("^ADD\\s+(?:COLUMN\\s+)?(.+)$", FLAGS);
    private static final Pattern P_DROP_PK = Pattern.compile("^DROP\\s+PRIMARY\\s+KEY$", FLAGS);
    private static final Pattern P_DROP_INDEX = Pattern.compile("^DROP\\s+(?:INDEX|KEY)\\s+(" + Ident.ONE + ")$", FLAGS);
    private static final Pattern P_DROP_FK = Pattern.compile("^DROP\\s+FOREIGN\\s+KEY\\s+(" + Ident.ONE + ")$", FLAGS);
    private static final Pattern P_DROP_CONSTRAINT = Pattern.compile(
        "^DROP\\s+(?:CHECK|CONSTRAINT)\\s+(" + Ident.ONE + ")$", FLAGS);
    private static final Pattern P_DROP_COLUMN = Pattern.compile(
        "^DROP\\s+(?:COLUMN\\s+)?(?:IF\\s+EXISTS\\s+)?(" + Ident.ONE + ")$", FLAGS);
    private static final Pattern P_MODIFY = Pattern.compile("^MODIFY\\s+(?:COLUMN\\s+)?(.+)$", FLAGS);
    private static final Pattern P_CHANGE = Pattern.compile(
        "^CHANGE\\s+(?:COLUMN\\s+)?(" + Ident.ONE + ")\\s+(.+)$", FLAGS);
    private static final Pattern P_RENAME_COLUMN = Pattern.compile(
        "^RENAME\\s+COLUMN\\s+(" + Ident.ONE + ")\\s+TO\\s+(" + Ident.ONE + ")$", FLAGS);
    private static final Pattern P_RENAME_INDEX = Pattern.compile(
        "^RENAME\\s+(?:INDEX|KEY)\\s+(" + Ident.ONE + ")\\s+TO\\s+(" + Ident.ONE + ")$", FLAGS);
    private static final Pattern P_RENAME_TABLE = Pattern.compile(
        "^RENAME\\s+(?:TO\\s+|AS\\s+)?(" + Ident.QUALIFIED + ")$", FLAGS);
    private static final Pattern P_ALTER_COLUMN = Pattern.compile(
        "^ALTER\\s+(?:COLUMN\\s+)?(" + Ident.ONE + ")\\s+(SET\\s+DEFAULT.*|DROP\\s+DEFAULT|SET\\s+(?:VISIBLE|INVISIBLE))$",
        FLAGS);
    private static final Pattern P_ALTER_INDEX = Pattern.compile(
        "^ALTER\\s+INDEX\\s+(" + Ident.ONE + ")\\s+(?:VISIBLE|INVISIBLE)$", FLAGS);
    private static final Pattern P_CONVERT = Pattern.compile(
        "^CONVERT\\s+TO\\s+(?:CHARACTER\\s+SET|CHARSET)\\s*=?\\s*(\\w+)(?:\\s+COLLATE\\s*=?\\s*(\\w+))?$", FLAGS);
    private static final Pattern P_TABLE_CHARSET = Pattern.compile(
        "^(?:DEFAULT\\s+)?(?:(?:CHARACTER\\s+SET|CHARSET)\\s*=?\\s*(\\w+))?\\s*(?:(?:DEFAULT\\s+)?COLLATE\\s*=?\\s*(\\w+))?$",
        FLAGS);
    private static final Pattern P_ENGINE = Pattern.compile("^(?:ENGINE\\s*=?\\s*(\\w+)|FORCE)$", FLAGS);
    private static final Pattern P_ROW_FORMAT = Pattern.compile("^ROW_FORMAT\\s*=?\\s*(\\w+)$", FLAGS);
    private static final Pattern P_AUTO_INCREMENT = Pattern.compile("^AUTO_INCREMENT\\s*=?\\s*\\d+$", FLAGS);
    private static final Pattern P_COMMENT = Pattern.compile("^COMMENT\\s*=?\\s*'.*'$", FLAGS);

    static Clause clause(String text) {
        Matcher m;
        if ((m = P_ALGORITHM.matcher(text)).matches()) {
            return value(Kind.ALGORITHM, text, m.group(1));
        }
        if ((m = P_LOCK.matcher(text)).matches()) {
            return value(Kind.LOCK, text, m.group(1));
        }
        if ((m = P_ADD_PK.matcher(text)).matches()) {
            IndexDef index = new IndexDef("PRIMARY", true, false, false, true, parts(inner(m.group(1))));
            return new Clause(Kind.ADD_PRIMARY_KEY, text, "PRIMARY", null, null, index, null, null, null);
        }
        if ((m = P_ADD_FK.matcher(text)).find()) {
            List<IndexPart> columns = parts(inner(m.group(2)));
            IndexDef index = new IndexDef(m.group(1) == null ? null : Ident.unquote(m.group(1)), false, false, false,
                false, columns);
            return new Clause(Kind.ADD_FOREIGN_KEY, text, index.name(), null, null, index, null, null,
                String.join(".", Ident.split(m.group(3))).replaceFirst("^null\\.", ""));
        }
        if (text.toUpperCase(Locale.ROOT).matches("^ADD\\s+(CONSTRAINT\\s+\\S+\\s+)?(UNIQUE|FULLTEXT|SPATIAL|INDEX|KEY)\\b.*")
            && (m = P_ADD_INDEX.matcher(text)).matches()) {
            String kind = m.group(1) == null ? "" : m.group(1).toUpperCase(Locale.ROOT);
            IndexDef index = new IndexDef(m.group(2) == null ? null : Ident.unquote(m.group(2)),
                kind.equals("UNIQUE"), kind.equals("FULLTEXT"), kind.equals("SPATIAL"), false, parts(inner(m.group(3))));
            return new Clause(Kind.ADD_INDEX, text, index.name(), null, null, index, null, null, null);
        }
        if (text.toUpperCase(Locale.ROOT).matches("^ADD\\s+(PARTITION|CHECK|CONSTRAINT)\\b.*")) {
            return Clause.of(Kind.UNKNOWN, text);
        }
        if ((m = P_ADD_COLUMN.matcher(text)).matches()) {
            ColumnDef column = columnDef(m.group(1));
            return column == null ? Clause.of(Kind.UNKNOWN, text)
                : new Clause(Kind.ADD_COLUMN, text, column.name(), null, column, null, null, null, null);
        }
        if (P_DROP_PK.matcher(text).matches()) {
            return new Clause(Kind.DROP_PRIMARY_KEY, text, "PRIMARY", null, null, null, null, null, null);
        }
        if ((m = P_DROP_INDEX.matcher(text)).matches()) {
            return named(Kind.DROP_INDEX, text, m.group(1));
        }
        if ((m = P_DROP_FK.matcher(text)).matches()) {
            return named(Kind.DROP_FOREIGN_KEY, text, m.group(1));
        }
        if ((m = P_DROP_CONSTRAINT.matcher(text)).matches()) {
            return named(Kind.DROP_CONSTRAINT, text, m.group(1));
        }
        if ((m = P_DROP_COLUMN.matcher(text)).matches()) {
            return named(Kind.DROP_COLUMN, text, m.group(1));
        }
        if ((m = P_MODIFY.matcher(text)).matches()) {
            ColumnDef column = columnDef(m.group(1));
            return column == null ? Clause.of(Kind.UNKNOWN, text)
                : new Clause(Kind.MODIFY_COLUMN, text, column.name(), null, column, null, null, null, null);
        }
        if ((m = P_CHANGE.matcher(text)).matches()) {
            ColumnDef column = columnDef(m.group(2));
            return column == null ? Clause.of(Kind.UNKNOWN, text)
                : new Clause(Kind.CHANGE_COLUMN, text, Ident.unquote(m.group(1)), column.name(), column, null, null,
                    null, null);
        }
        if ((m = P_RENAME_COLUMN.matcher(text)).matches()) {
            return new Clause(Kind.RENAME_COLUMN, text, Ident.unquote(m.group(1)), Ident.unquote(m.group(2)), null,
                null, null, null, null);
        }
        if ((m = P_RENAME_INDEX.matcher(text)).matches()) {
            return new Clause(Kind.RENAME_INDEX, text, Ident.unquote(m.group(1)), Ident.unquote(m.group(2)), null,
                null, null, null, null);
        }
        if ((m = P_RENAME_TABLE.matcher(text)).matches()) {
            return new Clause(Kind.RENAME_TABLE, text, null, Ident.unquote(m.group(1)), null, null, null, null, null);
        }
        if ((m = P_ALTER_INDEX.matcher(text)).matches()) {
            return named(Kind.INDEX_VISIBILITY, text, m.group(1));
        }
        if ((m = P_ALTER_COLUMN.matcher(text)).matches()) {
            Kind kind = m.group(2).toUpperCase(Locale.ROOT).contains("VISIBLE") ? Kind.COLUMN_VISIBILITY
                : Kind.COLUMN_DEFAULT;
            return named(kind, text, m.group(1));
        }
        if ((m = P_CONVERT.matcher(text)).matches()) {
            return new Clause(Kind.CONVERT_CHARSET, text, null, null, null, null, lower(m.group(1)), lower(m.group(2)),
                null);
        }
        if ((m = P_ENGINE.matcher(text)).matches()) {
            return value(Kind.REBUILD, text, m.group(1) == null ? "FORCE" : m.group(1));
        }
        if ((m = P_ROW_FORMAT.matcher(text)).matches()) {
            return value(Kind.ROW_FORMAT, text, m.group(1));
        }
        if (P_AUTO_INCREMENT.matcher(text).matches()) {
            return Clause.of(Kind.AUTO_INCREMENT, text);
        }
        if (P_COMMENT.matcher(text).matches()) {
            return Clause.of(Kind.COMMENT, text);
        }
        if ((m = P_TABLE_CHARSET.matcher(text)).matches() && (m.group(1) != null || m.group(2) != null)) {
            return new Clause(Kind.TABLE_DEFAULT_CHARSET, text, null, null, null, null, lower(m.group(1)),
                lower(m.group(2)), null);
        }
        return Clause.of(Kind.UNKNOWN, text);
    }

    private static Clause value(Kind kind, String text, String value) {
        return new Clause(kind, text, null, null, null, null, value.toUpperCase(Locale.ROOT), null, null);
    }

    private static Clause named(Kind kind, String text, String name) {
        return new Clause(kind, text, Ident.unquote(name), null, null, null, null, null, null);
    }

    private static final Pattern COLUMN = Pattern.compile(
        "^(" + Ident.ONE + ")\\s+(\\w+)\\s*(?:\\(([^)]*)\\))?(.*)$", FLAGS);
    private static final Pattern CHARSET = Pattern.compile("(?:CHARACTER\\s+SET|CHARSET)\\s*=?\\s*(\\w+)", FLAGS);
    private static final Pattern COLLATE = Pattern.compile("COLLATE\\s*=?\\s*(\\w+)", FLAGS);

    static ColumnDef columnDef(String text) {
        Matcher m = COLUMN.matcher(text.trim());
        if (!m.matches()) {
            return null;
        }
        String type = m.group(2).toUpperCase(Locale.ROOT);
        Integer length = null;
        Integer scale = null;
        if (m.group(3) != null && !type.equals("ENUM") && !type.equals("SET")) {
            String[] args = m.group(3).split(",");
            length = integer(args[0]);
            scale = args.length > 1 ? integer(args[1]) : null;
        }
        String rest = m.group(4);
        String upper = rest.toUpperCase(Locale.ROOT);
        Matcher charset = CHARSET.matcher(rest);
        Matcher collate = COLLATE.matcher(rest);
        Boolean nullable = upper.matches("(?s).*\\bNOT\\s+NULL\\b.*") ? Boolean.FALSE
            : upper.matches("(?s).*\\bNULL\\b.*") ? Boolean.TRUE : null;
        boolean generated = upper.matches("(?s).*\\b(GENERATED\\s+ALWAYS\\s+)?AS\\s*\\(.*");
        return new ColumnDef(Ident.unquote(m.group(1)), type, length, scale,
            charset.find() ? lower(charset.group(1)) : null,
            collate.find() ? lower(collate.group(1)) : null,
            nullable, upper.matches("(?s).*\\bDEFAULT\\b.*"), generated,
            generated && upper.matches("(?s).*\\bSTORED\\b.*"));
    }

    private static Integer integer(String text) {
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final Pattern PART = Pattern.compile("^(" + Ident.ONE + ")\\s*(?:\\((\\d+)\\))?(?:\\s+(?:ASC|DESC))?$",
        FLAGS);

    static List<IndexPart> parts(String list) {
        List<IndexPart> parts = new ArrayList<>();
        for (String part : splitTopLevel(list)) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            Matcher m = PART.matcher(p);
            if (m.matches()) {
                parts.add(new IndexPart(Ident.unquote(m.group(1)), m.group(2) == null ? null : Integer.valueOf(m.group(2)),
                    false));
            } else {
                parts.add(new IndexPart(p, null, true));
            }
        }
        return parts;
    }

    private static String inner(String parenthesised) {
        int depth = 0;
        for (int i = 0; i < parenthesised.length(); i++) {
            char c = parenthesised.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return parenthesised.substring(1, i);
                }
            }
        }
        return parenthesised.length() > 1 ? parenthesised.substring(1) : "";
    }

    static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        parts.add(current.toString());
        return parts;
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    static final class Ident {
        static final String ONE = "(?:`[^`]+`|[\\w$]+)";
        static final String QUALIFIED = ONE + "(?:\\s*\\.\\s*" + ONE + ")?";

        private Ident() { }

        static String unquote(String identifier) {
            String s = identifier.trim();
            return s.startsWith("`") && s.endsWith("`") ? s.substring(1, s.length() - 1) : s;
        }

        static String[] split(String qualified) {
            List<String> parts = new ArrayList<>();
            Matcher m = Pattern.compile(ONE).matcher(qualified);
            while (m.find()) {
                parts.add(unquote(m.group()));
            }
            return parts.size() > 1 ? new String[] {parts.get(0), parts.get(1)} : new String[] {null, parts.get(0)};
        }
    }
}
