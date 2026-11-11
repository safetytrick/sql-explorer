package com.github.safetytrick.sqlexplorer.schema;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a character column most likely holds, guessed from its name and type
 * alone — no data is read. Three kinds, because they want different things
 * from a charset or collation change:
 *
 * <ul>
 *   <li><b>identifier</b> — keys, codes, hashes, emails: compared for exact
 *       equality and joined on, so a collation change alters what matches
 *       and which unique keys collide;</li>
 *   <li><b>enum</b> — a small fixed vocabulary (status, type): ASCII in
 *       practice, so a conversion is a formality;</li>
 *   <li><b>text</b> — what people typed: names, descriptions, notes. This is
 *       where non-ASCII data, and therefore mis-encoded data, lives.</li>
 * </ul>
 *
 * <p>Every answer is {@code inferred} confidence and carries the rule that
 * produced it as its basis. It is a starting point for classification
 * (sqlx-n5j.6), which will add sampled and confirmed answers on top; nothing
 * here should be read as fact.
 */
public final class PresumedKind {

    public static final String IDENTIFIER = "identifier";
    public static final String ENUM = "enum";
    public static final String TEXT = "text";

    /** A guess and why. */
    public record Presumption(String kind, String basis) { }

    private static final Pattern IDENTIFIER_NAME = Pattern.compile(
        "(^|_)(id|ids|uuid|guid|key|code|hash|md5|sha1|sha256|digest|checksum|fingerprint|"
            + "token|sku|ref|reference|slug|email|username|login|hostname|host|ip|mac|isbn|iso)$"
            + "|^(id|uuid|guid|email|username|login|slug)(_|$)"
            + "|(Id|ID|Uuid|Key|Code|Hash|Token|Email)$");

    private static final Pattern ENUM_NAME = Pattern.compile(
        "(^|_)(status|state|type|kind|category|mode|role|level|priority|stage|phase|flag|"
            + "gender|currency|country|locale|lang|language|timezone|tz|unit|direction|severity|"
            + "source|channel|method|format)$"
            + "|(Status|State|Type|Kind|Mode|Role|Level)$");

    private static final Set<String> TEXT_TYPES = Set.of(
        "tinytext", "text", "mediumtext", "longtext", "json");

    private PresumedKind() {
    }

    /**
     * @param name     the column's name
     * @param typeName its SQL type name as the driver reports it ({@code VARCHAR}, {@code ENUM}, …)
     * @param size     its declared length in characters; 0 when unknown
     */
    public static Presumption of(String name, String typeName, int size) {
        String type = typeName == null ? "" : typeName.toLowerCase(Locale.ROOT);
        String column = name == null ? "" : name;
        String lower = column.toLowerCase(Locale.ROOT);

        if (type.startsWith("enum") || type.startsWith("set")) {
            return new Presumption(ENUM, "declared " + type.toUpperCase(Locale.ROOT));
        }
        if (TEXT_TYPES.contains(type)) {
            return new Presumption(TEXT, "a " + type.toUpperCase(Locale.ROOT) + " column");
        }
        if (IDENTIFIER_NAME.matcher(column).find() || IDENTIFIER_NAME.matcher(lower).find()) {
            return new Presumption(IDENTIFIER, "named like a key or code");
        }
        if (ENUM_NAME.matcher(column).find() || ENUM_NAME.matcher(lower).find()) {
            return size > 0 && size > 64
                ? new Presumption(TEXT, "named like a category, but " + size + " characters wide")
                : new Presumption(ENUM, "named like a status or category");
        }
        // CHAR(n) is fixed width: hashes, uuids and codes, rarely prose
        if ("char".equals(type) && size > 0 && size <= 64) {
            return new Presumption(IDENTIFIER, "fixed-width CHAR(" + size + ")");
        }
        return new Presumption(TEXT, "no rule matched; free text until shown otherwise");
    }
}
