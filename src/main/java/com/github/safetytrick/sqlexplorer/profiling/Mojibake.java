package com.github.safetytrick.sqlexplorer.profiling;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Undoes a character-set mistake on the few characters a {@link Signature}
 * matched, to say which character was meant: {@code Ã©} → é. Works in the
 * application, on the matched characters only — never on a stored value.
 *
 * <p>Windows-1252 is spelt out rather than taken from the JDK: MySQL's latin1
 * keeps the five bytes Windows-1252 leaves undefined (81, 8D, 8F, 90, 9D) as
 * U+0081 … U+009D, and the JDK's {@code windows-1252} refuses to encode them.
 */
final class Mojibake {

    /** Windows-1252 at 80–9F; -1 where it is undefined and MySQL keeps the C1 code point. */
    private static final int[] HIGH = {
        0x20AC, -1, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021,
        0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, -1, 0x017D, -1,
        -1, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
        0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, -1, 0x017E, 0x0178
    };

    private Mojibake() { }

    /**
     * The text after undoing {@code passes} rounds of "UTF-8 read as
     * Windows-1252"; null when a round does not produce valid UTF-8, which
     * means the guess was wrong.
     */
    static String undo(String text, int passes) {
        String current = text;
        for (int i = 0; i < passes && current != null; i++) {
            byte[] bytes = toWindows1252(current);
            current = bytes == null ? null : utf8(bytes);
        }
        return current;
    }

    /**
     * {@link #undo} on the longest prefix that decodes: a greedy match may run
     * into the next character's sequence.
     */
    static String undoLongest(String text, int passes) {
        for (int end = text.length(); end > 1; end--) {
            String decoded = undo(text.substring(0, end), passes);
            if (decoded != null && !decoded.isEmpty()) {
                return decoded;
            }
        }
        return null;
    }

    /** C1 code points read as the Windows-1252 bytes they were: U+0093 → “. Null for an undefined byte. */
    static String windows1252(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x80 && c <= 0x9F) {
                int mapped = HIGH[c - 0x80];
                if (mapped < 0) {
                    return null;
                }
                out.append((char) mapped);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static byte[] toWindows1252(String text) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < text.length(); i++) {
            int b = byteFor(text.charAt(i));
            if (b < 0) {
                return null;
            }
            bytes.write(b);
        }
        return bytes.toByteArray();
    }

    private static int byteFor(char c) {
        if (c < 0x80 || c >= 0xA0 && c <= 0xFF) {
            return c;
        }
        if (c <= 0x9F) {
            // only the undefined five survive as C1 code points
            return HIGH[c - 0x80] < 0 ? c : -1;
        }
        for (int i = 0; i < HIGH.length; i++) {
            if (HIGH[i] == c) {
                return 0x80 + i;
            }
        }
        return -1;
    }

    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
