package com.github.safetytrick.sqlexplorer.schema;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class PresumedKind {

    public static final String IDENTIFIER = "identifier";
    public static final String ENUM = "enum";
    public static final String TEXT = "text";

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
        if ("char".equals(type) && size > 0 && size <= 64) {
            return new Presumption(IDENTIFIER, "fixed-width CHAR(" + size + ")");
        }
        return new Presumption(TEXT, "no rule matched; free text until shown otherwise");
    }
}
