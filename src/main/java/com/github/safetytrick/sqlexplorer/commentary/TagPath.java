package com.github.safetytrick.sqlexplorer.commentary;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a tag points at, parsed from its path.
 *
 * <p>THE ONE PLACE THAT KNOWS THE SHAPES. There are exactly three:
 * {@code /schema/{s}}, {@code /schema/{s}/table/{t}} and
 * {@code /query/{digest}}. They were previously read back out by two different
 * regexes — one deriving the type from the tail of the string, one collecting
 * key/value pairs — and neither knew what a valid path looked like.
 *
 * <p>THE BUG THAT MOTIVATED THIS. MySQL identifiers may contain {@code /} when
 * quoted. Given a real table named {@code a/b}, the old tail regex
 * {@code ([^/]+)/[^/]+$} derived the type of
 * {@code /schema/probe/table/a/b} as {@code "a"} — a type that does not exist.
 * A tag typed {@code a} is invisible to {@code findAllByType("table")}, so no
 * lens, filter or bulk operation would ever find it, and a nonexistent type
 * appeared in the tag manager's list. The path is client-supplied, so any
 * client could produce that row (sqlx-0lj).
 *
 * <p>THE LEGACY WIRE FORMAT. Targets are stated typed now ({@link TagTarget}),
 * so this exists to read the path form the API still accepts and the links that
 * still carry one. A name containing {@code /} has no path form at all, which is
 * exactly why the typed form had to replace it (sqlx-3ai).
 *
 * <p>PARSING IS STRICT, AND REJECTS RATHER THAN GUESSES. A segment may not
 * contain {@code /}, so {@code /schema/probe/table/a/b} is refused instead of
 * being read as some other table. That makes a table named {@code a/b}
 * un-taggable, which is the honest failure: the path format genuinely cannot
 * address it. Representing it needs either encoded segments — which would push
 * the theoretical path length past the bound the unique-key prefix relies on —
 * or typed columns (sqlx-11v). Refusing is the correct behaviour until one of
 * those lands, because the alternative is a row nobody can find.
 */
record TagPath(Target target, String schema, String table, String digest) {

    /** The kind of thing a path names. */
    enum Target {
        SCHEMA("schema"), TABLE("table"), QUERY("query");

        private final String type;

        Target(String type) {
            this.type = type;
        }

        /** The value stored in {@code tags.type}. */
        String type() {
            return type;
        }
    }

    private static final String SCHEMA_PREFIX = "/schema/";
    private static final String TABLE_INFIX = "/table/";
    private static final String QUERY_PREFIX = "/query/";

    /**
     * Parse a path, or null when it is not one of the three shapes.
     *
     * <p>Null rather than an exception because this is also the read path: a row
     * already stored with a path this cannot read must degrade to "no elements"
     * rather than break the page listing it.
     */
    static TagPath parse(String path) {
        if (path == null) {
            return null;
        }
        if (path.startsWith(QUERY_PREFIX)) {
            String digest = path.substring(QUERY_PREFIX.length());
            return segment(digest) ? new TagPath(Target.QUERY, null, null, digest) : null;
        }
        if (!path.startsWith(SCHEMA_PREFIX)) {
            return null;
        }
        String rest = path.substring(SCHEMA_PREFIX.length());
        int infix = rest.indexOf(TABLE_INFIX);
        if (infix < 0) {
            return segment(rest) ? new TagPath(Target.SCHEMA, rest, null, null) : null;
        }
        String schema = rest.substring(0, infix);
        String table = rest.substring(infix + TABLE_INFIX.length());
        return segment(schema) && segment(table)
            ? new TagPath(Target.TABLE, schema, table, null)
            : null;
    }

    /**
     * Parse a path being written, refusing one that is not addressable.
     *
     * @throws IllegalArgumentException naming the shapes, so a caller that got
     *                                  it wrong is told what was expected
     */
    static TagPath require(String path) {
        TagPath parsed = parse(path);
        if (parsed == null) {
            throw new IllegalArgumentException(
                "not a tag path: " + path + " — expected /schema/{schema}, "
                    + "/schema/{schema}/table/{table} or /query/{digest}, "
                    + "with no '/' inside a name");
        }
        return parsed;
    }

    /** A non-empty name containing no separator. */
    private static boolean segment(String value) {
        return !value.isEmpty() && value.indexOf('/') < 0;
    }

    /** The value stored in {@code tags.type}. */
    String type() {
        return target.type();
    }

    /** This address as a typed target. */
    TagTarget toTarget() {
        return new TagTarget(target.type(), schema, table, digest).normalized();
    }

    /**
     * The path's named parts, keyed as the type names that address them.
     *
     * <p>Keyed this way because callers look parts up by the tag's own type —
     * {@code elements().get(tag.getType())} is how a query tag's digest is
     * read — so the keys have to agree with {@link #type()}.
     */
    Map<String, String> elements() {
        Map<String, String> elements = new LinkedHashMap<>();
        if (schema != null) {
            elements.put(Target.SCHEMA.type(), schema);
        }
        if (table != null) {
            elements.put(Target.TABLE.type(), table);
        }
        if (digest != null) {
            elements.put(Target.QUERY.type(), digest);
        }
        return elements;
    }
}
