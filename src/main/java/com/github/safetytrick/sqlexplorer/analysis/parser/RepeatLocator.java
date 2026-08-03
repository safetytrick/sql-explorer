package com.github.safetytrick.sqlexplorer.analysis.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Locates the surviving instance of each collapsed repeat inside formatted
 * SQL text. Matching is token-based and whitespace/case-insensitive, because
 * the display formatter re-flows the normalizer's serialization across lines
 * and re-cases keywords. Repeats are located in order with a moving cursor
 * (falling back to a from-the-top scan), so the same snippet appearing twice
 * resolves to successive positions; a snippet that can't be found is dropped
 * rather than misattributed.
 */
public final class RepeatLocator {

    /** A located repeat: [start, end) offsets in the text, and the sample's occurrence count. */
    public record Located(int start, int end, int count) { }

    private static final Pattern TOKEN = Pattern.compile("`[^`]*`|'[^']*'|[A-Za-z0-9_$#]+|\\S");

    private RepeatLocator() { }

    public static List<Located> locate(String text, List<QueryNormalizer.Repeat> repeats) {
        if (repeats.isEmpty()) {
            return List.of();
        }
        List<Token> textTokens = tokenize(text);
        List<Located> located = new ArrayList<>();
        int cursor = 0;
        for (QueryNormalizer.Repeat repeat : repeats) {
            List<Token> snippetTokens = tokenize(repeat.snippet());
            if (snippetTokens.isEmpty()) {
                continue;
            }
            int match = indexOf(textTokens, snippetTokens, cursor);
            if (match < 0) {
                match = indexOf(textTokens, snippetTokens, 0);
            }
            if (match < 0) {
                continue;
            }
            Token first = textTokens.get(match);
            Token last = textTokens.get(match + snippetTokens.size() - 1);
            located.add(new Located(first.start, last.end, repeat.count()));
            cursor = match + snippetTokens.size();
        }
        return located;
    }

    private static int indexOf(List<Token> text, List<Token> snippet, int from) {
        outer:
        for (int i = from; i <= text.size() - snippet.size(); i++) {
            for (int j = 0; j < snippet.size(); j++) {
                if (!text.get(i + j).value.equalsIgnoreCase(snippet.get(j).value)) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            tokens.add(new Token(matcher.group(), matcher.start(), matcher.end()));
        }
        return tokens;
    }

    private record Token(String value, int start, int end) { }
}
