package com.github.safetytrick.sqlexplorer.profiling;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.profiling.ProfileResult.CharacterCount;
import com.github.safetytrick.sqlexplorer.profiling.ProfileResult.ColumnResult;
import com.github.safetytrick.sqlexplorer.profiling.ProfileResult.ServerIds;
import com.github.safetytrick.sqlexplorer.profiling.ProfileResult.ServerResult;
import com.github.safetytrick.sqlexplorer.profiling.ProfileResult.SignatureResult;
import com.github.safetytrick.sqlexplorer.profiling.Signature.Family;

/**
 * What a column's figures mean for a conversion to utf8mb4, in a sentence,
 * and the figures of every server added together. A sample never says a
 * column <i>is</i> anything: "no non-ASCII in 25,000 sampled rows" is not
 * "the column is ASCII", and the sentences keep that difference.
 */
final class Verdicts {

    private Verdicts() { }

    /** 20,000 rather than 20000. */
    private static String n(long value) {
        return String.format(java.util.Locale.ROOT, "%,d", value);
    }

    static String of(String characterSet, long rows, long nonAscii, List<SignatureResult> signatures,
                     boolean complete) {
        if (rows == 0) {
            return "no rows read";
        }
        String scope = complete ? "in every row (" + n(rows) + ")" : "in " + n(rows) + " sampled rows";
        if (nonAscii == 0) {
            return complete ? "ASCII only, every row read: any conversion is safe"
                : "no non-ASCII " + scope + "; not proof the column is ASCII";
        }
        Map<Signature, Long> found = new EnumMap<>(Signature.class);
        signatures.forEach(s -> found.put(s.signature(), s.rows()));
        Family family = Family.of(characterSet);
        if (family == Family.LATIN1) {
            long utf8 = found.getOrDefault(Signature.UTF8_IN_LATIN1, 0L);
            long latin1 = found.getOrDefault(Signature.LATIN1_TEXT, 0L);
            if (utf8 > 0 && latin1 > 0) {
                return "mixed: UTF-8 bytes in " + n(utf8) + " rows and genuine latin1 text in " + n(latin1) + " rows "
                    + scope + "; no single conversion is right for both";
            }
            if (utf8 > 0) {
                return "UTF-8 bytes in " + n(utf8) + " rows " + scope + ": convert through VARBINARY, not CONVERT TO";
            }
            return n(nonAscii) + " non-ASCII rows " + scope + ", read as genuine latin1: CONVERT TO utf8mb4 fits";
        }
        if (family == Family.UTF8) {
            List<String> problems = new ArrayList<>();
            for (Signature s : List.of(Signature.DOUBLE_ENCODED, Signature.TRIPLE_ENCODED, Signature.C1_CONTROL,
                Signature.REPLACEMENT_CHARACTER)) {
                long count = found.getOrDefault(s, 0L);
                if (count > 0) {
                    problems.add(s.label() + " in " + n(count) + " rows");
                }
            }
            return problems.isEmpty() ? n(nonAscii) + " non-ASCII rows " + scope + ", no corruption signature found"
                : String.join(", ", problems) + " " + scope;
        }
        return n(nonAscii) + " non-ASCII rows " + scope + "; no signatures are defined for " + characterSet;
    }

    /** Every done server's columns added together, by column name. */
    static List<ColumnResult> combine(List<ServerResult> servers) {
        Map<String, List<ColumnResult>> byColumn = new LinkedHashMap<>();
        boolean complete = !servers.isEmpty();
        for (ServerResult server : servers) {
            if (!"done".equals(server.status())) {
                complete = false;
                continue;
            }
            complete &= server.complete();
            server.columns().forEach(c -> byColumn.computeIfAbsent(c.column(), k -> new ArrayList<>()).add(c));
        }
        List<ColumnResult> out = new ArrayList<>();
        for (List<ColumnResult> parts : byColumn.values()) {
            ColumnResult first = parts.getFirst();
            long rows = parts.stream().mapToLong(ColumnResult::rows).sum();
            long nulls = parts.stream().mapToLong(ColumnResult::nulls).sum();
            long nonAscii = parts.stream().mapToLong(ColumnResult::nonAscii).sum();
            Long maxBytes = parts.stream().map(ColumnResult::maxBytes).filter(b -> b != null)
                .max(Long::compare).orElse(null);
            long distinct = parts.stream().mapToLong(ColumnResult::distinctAtLeast).max().orElse(0);
            List<SignatureResult> signatures = new ArrayList<>();
            for (SignatureResult s : first.signatures()) {
                List<SignatureResult> same = parts.stream().flatMap(p -> p.signatures().stream())
                    .filter(o -> o.signature() == s.signature()).toList();
                List<ServerIds> ids = same.stream().flatMap(o -> o.ids().stream()).toList();
                Map<String, Long> characters = new LinkedHashMap<>();
                same.forEach(o -> o.characters().forEach(c -> characters.merge(c.matched(), c.rows(), Long::sum)));
                List<CharacterCount> merged = ProfileRunner.characterCounts(s.signature(), characters);
                signatures.add(new SignatureResult(s.signature(), s.label(), s.explanation(), s.heuristic(),
                    s.informational(), same.stream().mapToLong(SignatureResult::rows).sum(), ids, merged));
            }
            out.add(new ColumnResult(first.column(), first.type(), first.characterSet(), first.collation(), rows,
                nulls, nonAscii, maxBytes, distinct, signatures,
                of(first.characterSet(), rows, nonAscii, signatures, complete)));
        }
        return out;
    }

    /** {@code confirmed} only when every server finished and read every row. */
    static String confidence(List<ServerResult> servers) {
        return !servers.isEmpty() && servers.stream().allMatch(s -> "done".equals(s.status()) && s.complete())
            ? "confirmed" : "sampled";
    }
}
