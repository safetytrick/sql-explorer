package com.github.safetytrick.sqlexplorer.schema;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A schema's fingerprint: one hash per table, keyed {@code schema.table}
 * lowercase. Equal fingerprints mean the schema loader would read the same
 * thing; {@link #differingTables} says where two do not agree.
 *
 * @param tables table key → that table's hash, sorted so the digest is stable
 */
public record SchemaFingerprint(Map<String, String> tables) {

    public SchemaFingerprint {
        tables = Map.copyOf(new TreeMap<>(tables));
    }

    /** The table key used throughout: lowercase {@code schema.table}, as MySQL names compare. */
    public static String key(String schema, String table) {
        return (schema + "." + table).toLowerCase(Locale.ROOT);
    }

    /** From the per-part hashes the fingerprint query returns, folded into one hash per table. */
    static SchemaFingerprint of(Map<String, Map<String, Long>> partsByTable) {
        Map<String, String> tables = new TreeMap<>();
        partsByTable.forEach((table, parts) -> {
            StringBuilder folded = new StringBuilder();
            new TreeMap<>(parts).forEach((part, hash) -> folded.append(part).append('=').append(hash).append(';'));
            tables.put(table, sha256(folded.toString()).substring(0, 16));
        });
        return new SchemaFingerprint(tables);
    }

    /** One value for the whole schema, for the cheap "has anything changed?" comparison. */
    public String digest() {
        StringBuilder all = new StringBuilder();
        new TreeMap<>(tables).forEach((table, hash) -> all.append(table).append('=').append(hash).append('\n'));
        return sha256(all.toString());
    }

    /** Tables present in only one of the two, or hashed differently: added, dropped or altered. */
    public Set<String> differingTables(SchemaFingerprint other) {
        Set<String> differing = new TreeSet<>();
        Set<String> all = new TreeSet<>(tables.keySet());
        all.addAll(other.tables.keySet());
        for (String table : all) {
            if (!Objects.equals(tables.get(table), other.tables.get(table))) {
                differing.add(table);
            }
        }
        return differing;
    }

    /** The same comparison, limited to the tables of one schema — what drift between shards compares. */
    public SchemaFingerprint onlySchema(String schema) {
        String prefix = schema.toLowerCase(Locale.ROOT) + ".";
        Map<String, String> subset = new TreeMap<>();
        tables.forEach((table, hash) -> {
            if (table.startsWith(prefix)) {
                subset.put(table, hash);
            }
        });
        return new SchemaFingerprint(subset);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
