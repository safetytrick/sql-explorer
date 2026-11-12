package com.github.safetytrick.sqlexplorer.profiling;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A recognisable trace a character-set mistake leaves in stored text
 * (sqlx-n5j.3), as a regular expression MySQL evaluates itself, so the value
 * never leaves the server: only how many rows matched, the keys of a few of
 * them, and the matched characters come back.
 *
 * <p>Patterns are ICU syntax ({@code REGEXP_LIKE}, {@code REGEXP_SUBSTR}) and
 * are sent as statement parameters, never spliced into SQL text. They are
 * matched on characters, after MySQL decodes the column, which is what makes
 * one pattern serve a utf8mb4 and a latin1 column alike: MySQL's latin1 is
 * Windows-1252, so UTF-8 bytes stored in a latin1 column decode to exactly the
 * characters a double-encoded utf8mb4 value holds ({@code Ã©} for é). Which of
 * the two it is depends on the column's character set, so a signature applies
 * to a {@link Family}.
 *
 * <p>Every count is of rows, and a row counts once per signature however many
 * matches it holds. A row with a triple-encoded value also matches the
 * double-encoded pattern; they are separate questions, not a partition.
 */
public enum Signature {

    DOUBLE_ENCODED(Family.UTF8, Patterns.MOJIBAKE, Decode.ONE_PASS, false, false,
        "double-encoded UTF-8",
        "UTF-8 bytes were read as latin1 (Windows-1252) and encoded to UTF-8 a second time. Typically an "
            + "application sent UTF-8 over a connection declared latin1 into a utf8mb4 column, or a latin1 column "
            + "holding UTF-8 bytes was converted with CONVERT TO CHARACTER SET. Each value can be repaired by "
            + "undoing one pass: CONVERT(CAST(CONVERT(col USING latin1) AS BINARY) USING utf8mb4), row by row "
            + "after checking it."),

    UTF8_IN_LATIN1(Family.LATIN1, Patterns.MOJIBAKE, Decode.ONE_PASS, false, false,
        "UTF-8 bytes in a latin1 column",
        "The application wrote UTF-8 over a latin1 connection, so the column holds UTF-8 bytes it calls "
            + "latin1. It reads back correctly over the same connection, but ALTER TABLE … CONVERT TO CHARACTER "
            + "SET utf8mb4 will double-encode every one of these values. Convert through VARBINARY instead "
            + "(MODIFY the column to VARBINARY, then to the utf8mb4 type), which reinterprets the bytes without "
            + "transcoding them — but only if the column holds no genuine latin1 text as well."),

    TRIPLE_ENCODED(Family.ANY, Patterns.TRIPLE, Decode.TWO_PASSES, false, false,
        "encoded twice over",
        "The double-encoding mistake was made a second time: a value that was already double-encoded passed "
            + "through another latin1 connection or conversion. Each pass has to be undone; the characters "
            + "shown decode through both."),

    REPLACEMENT_CHARACTER(Family.ANY, Patterns.REPLACEMENT, Decode.NONE, false, false,
        "replacement character",
        "U+FFFD, the Unicode replacement character, is stored (or its UTF-8 bytes, ï¿½). Something before "
            + "MySQL failed to decode the original text and replaced the character. It cannot be recovered from "
            + "the database; only the source of the data still has it."),

    C1_CONTROL(Family.UTF8, Patterns.C1, Decode.WINDOWS_1252, false, false,
        "C1 control characters",
        "Characters U+0080–U+009F are control codes nobody types. Windows-1252 text — smart quotes, dashes, "
            + "€, ™ — was decoded as ISO-8859-1 instead of Windows-1252 on its way in. Each maps back through "
            + "Windows-1252 to the character shown."),

    LATIN1_TEXT(Family.LATIN1, Patterns.LATIN1_TEXT, Decode.SAME, false, false,
        "genuine latin1 text",
        "Accented letters stored as single latin1 bytes, as the column declares. CONVERT TO CHARACTER SET "
            + "utf8mb4 converts these correctly. If the same column also holds UTF-8 bytes, no single "
            + "conversion is right for both, and the two kinds of row have to be converted separately."),

    WINDOWS_1252_PUNCTUATION(Family.LATIN1, Patterns.WINDOWS_1252, Decode.SAME, false, true,
        "Windows-1252 punctuation",
        "Smart quotes, dashes, €, ™ and the like in a latin1 column. MySQL's latin1 is Windows-1252, so these "
            + "convert to utf8mb4 correctly; listed for information, not as a problem."),

    QUESTION_MARK(Family.ANY, Patterns.QUESTION_MARK, Decode.NONE, true, false,
        "? between letters",
        "A character the connection could not represent — an emoji, or a non-Latin script over a latin1 "
            + "connection — is stored as a literal ?. The original is lost. Low confidence: URLs, query strings "
            + "and genuine questions match the same pattern.");

    /** Which column character sets a signature means something in. */
    public enum Family {
        UTF8, LATIN1, OTHER, ANY;

        /** {@code utf8mb4}, {@code utf8mb3} and {@code utf8} are UTF8; {@code latin1} is LATIN1. */
        public static Family of(String characterSet) {
            String charset = characterSet == null ? "" : characterSet.toLowerCase(Locale.ROOT);
            if (charset.startsWith("utf8")) {
                return UTF8;
            }
            return "latin1".equals(charset) ? LATIN1 : OTHER;
        }
    }

    /** How the characters a match holds turn back into the one most likely meant. */
    enum Decode { ONE_PASS, TWO_PASSES, WINDOWS_1252, SAME, NONE }

    private final Family family;
    private final String pattern;
    private final Decode decode;
    private final boolean heuristic;
    private final boolean informational;
    private final String label;
    private final String explanation;

    Signature(Family family, String pattern, Decode decode, boolean heuristic, boolean informational, String label,
              String explanation) {
        this.family = family;
        this.pattern = pattern;
        this.decode = decode;
        this.heuristic = heuristic;
        this.informational = informational;
        this.label = label;
        this.explanation = explanation;
    }

    /** The signatures that mean something in a column of this character set, in declaration order. */
    public static List<Signature> forCharacterSet(String characterSet) {
        Family family = Family.of(characterSet);
        if (family == Family.OTHER) {
            return List.of();
        }
        return Arrays.stream(values()).filter(s -> s.family == Family.ANY || s.family == family).toList();
    }

    /** The ICU pattern, matched case-sensitively ({@code 'c'}): Ã and ã are different evidence. */
    public String pattern() {
        return pattern;
    }

    /** Whether the matched characters are worth listing: each one, with what it most likely was. */
    public boolean listsCharacters() {
        return decode != Decode.NONE;
    }

    /** A pattern that also matches ordinary text, recorded at a lower confidence. */
    public boolean heuristic() {
        return heuristic;
    }

    /** Not a problem: present so the count is not mistaken for one. */
    public boolean informational() {
        return informational;
    }

    public String label() {
        return label;
    }

    public String explanation() {
        return explanation;
    }

    /** The character most likely meant by these matched characters; null when it cannot be told. */
    public String intended(String matched) {
        return switch (decode) {
            case ONE_PASS -> Mojibake.undo(matched, 1);
            case TWO_PASSES -> Mojibake.undoLongest(matched, 2);
            case WINDOWS_1252 -> Mojibake.windows1252(matched);
            case SAME -> matched;
            case NONE -> null;
        };
    }

    /** Matches any character outside ASCII: the share of values that a charset change can affect at all. */
    public static final String NON_ASCII = "[^\\x{0000}-\\x{007F}]";

    /**
     * The patterns, built from the bytes UTF-8 uses: a lead byte (C2–F4) and
     * one to three continuation bytes (80–BF), each as the character
     * Windows-1252 decodes it to — 80–9F are €, ‚, ƒ, „ … rather than C1 codes.
     */
    static final class Patterns {

        /** A continuation byte 80–BF, as Windows-1252 decodes it (MySQL keeps 81, 8D, 8F, 90, 9D as U+0081 …). */
        static final String CONT = "[\\x{0080}-\\x{00BF}\\x{0152}\\x{0153}\\x{0160}\\x{0161}\\x{0178}\\x{017D}"
            + "\\x{017E}\\x{0192}\\x{02C6}\\x{02DC}\\x{2013}\\x{2014}\\x{2018}-\\x{201A}\\x{201C}-\\x{201E}"
            + "\\x{2020}-\\x{2022}\\x{2026}\\x{2030}\\x{2039}\\x{203A}\\x{20AC}\\x{2122}]";

        /** UTF-8 for one character, read as Windows-1252: a 2-, 3- or 4-byte sequence. */
        static final String MOJIBAKE = "[\\x{00C2}-\\x{00DF}]" + CONT
            + "|[\\x{00E0}-\\x{00EF}]" + CONT + CONT
            + "|[\\x{00F0}-\\x{00F4}]" + CONT + CONT + CONT;

        /**
         * Double encoding applied to a double encoding: Ã then ƒ (from Ã),
         * ‚ (from Â) or ¢ (from â), then the rest of the sequence as one to
         * three more {@link #MOJIBAKE} units, so the whole of it can be
         * decoded. ÃƒÂ© is é twice over; Ã¢â‚¬â„¢ is ’. Greedy, so it can take
         * a unit of the next character too; {@link Mojibake#undoLongest}
         * decodes the longest prefix that works.
         */
        static final String TRIPLE = "\\x{00C3}[\\x{0192}\\x{201A}\\x{00A2}](?:" + MOJIBAKE + "){1,3}";

        /** U+FFFD itself, or its UTF-8 bytes read as Windows-1252. */
        static final String REPLACEMENT = "\\x{FFFD}|\\x{00EF}\\x{00BF}\\x{00BD}";

        static final String C1 = "[\\x{0080}-\\x{009F}]";

        /** Not part of a sequence {@link #MOJIBAKE} matches: not one to three characters after a lead. */
        static final String NOT_AFTER_LEAD = "(?<![\\x{00C2}-\\x{00F4}]|[\\x{00E0}-\\x{00F4}]" + CONT
            + "|[\\x{00F0}-\\x{00F4}]" + CONT + CONT + ")";

        /** A latin1 letter or symbol that is not part of a UTF-8 sequence: neither after a lead nor one itself. */
        static final String LATIN1_TEXT = NOT_AFTER_LEAD + "[\\x{00A0}-\\x{00FF}](?!" + CONT + ")";

        /** What Windows-1252 puts at 80–9F, outside any UTF-8 sequence. */
        static final String WINDOWS_1252 = NOT_AFTER_LEAD + "[\\x{0152}\\x{0153}\\x{0160}\\x{0161}\\x{0178}"
            + "\\x{017D}\\x{017E}\\x{0192}\\x{02C6}\\x{02DC}\\x{2013}\\x{2014}\\x{2018}-\\x{201A}"
            + "\\x{201C}-\\x{201E}\\x{2020}-\\x{2022}\\x{2026}\\x{2030}\\x{2039}\\x{203A}\\x{20AC}\\x{2122}]";

        static final String QUESTION_MARK = "\\p{L}\\?\\p{L}";

        private Patterns() { }
    }
}
