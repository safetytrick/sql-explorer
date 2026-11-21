package com.github.safetytrick.sqlexplorer.profiling;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignatureTest {

    private static String first(Signature signature, String text) {
        Matcher m = Pattern.compile(signature.pattern()).matcher(text);
        return m.find() ? m.group() : null;
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "cafÃ© au lait|Ã©|é",
        "donâ€™t|â€™|’",
        "â€œquotedâ€\u009D|â€œ|“",
        "MÃ¼ller|Ã¼|ü",
        "20Â°C|Â°|°",
        "emoji ðŸ˜€ here|ðŸ˜€|😀"
    })
    void findsDoubleEncodingAndSaysWhatItWas(String text, String matched, String intended) {
        assertThat(first(Signature.DOUBLE_ENCODED, text)).isEqualTo(matched);
        assertThat(Signature.DOUBLE_ENCODED.intended(matched)).isEqualTo(intended == null || intended.isEmpty()
            ? null : intended);
    }

    @Test
    void leavesCorrectTextAlone() {
        assertThat(first(Signature.DOUBLE_ENCODED, "café, naïve, Müller, “quoted”, ’, €5")).isNull();
        assertThat(first(Signature.TRIPLE_ENCODED, "cafÃ©")).isNull();
        assertThat(first(Signature.C1_CONTROL, "plain “text”")).isNull();
    }

    @Test
    void findsTripleEncodingAndUndoesBothPasses() {
        assertThat(first(Signature.TRIPLE_ENCODED, "cafÃƒÂ© au")).isEqualTo("ÃƒÂ©");
        assertThat(Signature.TRIPLE_ENCODED.intended("ÃƒÂ©")).isEqualTo("é");
        assertThat(first(Signature.TRIPLE_ENCODED, "donÃ¢â‚¬â„¢t")).isEqualTo("Ã¢â‚¬â„¢");
        assertThat(Signature.TRIPLE_ENCODED.intended("Ã¢â‚¬â„¢")).isEqualTo("’");
        assertThat(Signature.TRIPLE_ENCODED.intended("ÃƒÂ©Ãƒ")).isEqualTo("é");
    }

    @Test
    void readsC1ControlsThroughWindows1252() {
        assertThat(first(Signature.C1_CONTROL, "\u0093quoted\u0094")).isEqualTo("\u0093");
        assertThat(Signature.C1_CONTROL.intended("\u0093")).isEqualTo("“");
        assertThat(Signature.C1_CONTROL.intended("\u0081")).isNull();
    }

    @Test
    void findsReplacementCharactersEitherWay() {
        assertThat(first(Signature.REPLACEMENT_CHARACTER, "caf�")).isEqualTo("�");
        assertThat(first(Signature.REPLACEMENT_CHARACTER, "cafï¿½")).isEqualTo("ï¿½");
        assertThat(Signature.REPLACEMENT_CHARACTER.listsCharacters()).isFalse();
    }

    @Test
    void tellsGenuineLatin1FromUtf8BytesInALatin1Column() {
        // what MySQL hands back for a latin1 column: genuine é is one character, UTF-8 é is Ã©
        assertThat(first(Signature.LATIN1_TEXT, "café")).isEqualTo("é");
        assertThat(first(Signature.LATIN1_TEXT, "cafÃ©")).isNull();
        assertThat(first(Signature.UTF8_IN_LATIN1, "cafÃ©")).isEqualTo("Ã©");
        assertThat(first(Signature.LATIN1_TEXT, "donâ€¢t")).isNull();
    }

    @Test
    void windows1252PunctuationOutsideASequenceIsInformation() {
        assertThat(first(Signature.WINDOWS_1252_PUNCTUATION, "it’s “fine” – €5")).isEqualTo("’");
        assertThat(first(Signature.WINDOWS_1252_PUNCTUATION, "donâ€™t")).isNull();
        assertThat(Signature.WINDOWS_1252_PUNCTUATION.informational()).isTrue();
    }

    @Test
    void aQuestionMarkBetweenLettersIsOnlyAHeuristic() {
        assertThat(first(Signature.QUESTION_MARK, "Jos? Garc?a")).isEqualTo("c?a");
        assertThat(first(Signature.QUESTION_MARK, "why? because")).isNull();
        assertThat(Signature.QUESTION_MARK.heuristic()).isTrue();
    }

    @Test
    void eachCharacterSetGetsItsOwnSignatures() {
        assertThat(Signature.forCharacterSet("utf8mb4")).contains(Signature.DOUBLE_ENCODED, Signature.C1_CONTROL)
            .doesNotContain(Signature.UTF8_IN_LATIN1, Signature.LATIN1_TEXT);
        assertThat(Signature.forCharacterSet("latin1")).contains(Signature.UTF8_IN_LATIN1, Signature.LATIN1_TEXT)
            .doesNotContain(Signature.DOUBLE_ENCODED, Signature.C1_CONTROL);
        assertThat(Signature.forCharacterSet("ascii")).isEmpty();
    }
}
