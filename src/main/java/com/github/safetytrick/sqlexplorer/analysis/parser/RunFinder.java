package com.github.safetytrick.sqlexplorer.analysis.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import org.hibernate.engine.jdbc.internal.BasicFormatterImpl;
import org.hibernate.engine.jdbc.internal.Formatter;

/**
 * Detects the most prominent run of *adjacent* (tandem) repeats in a string
 * and prints it collapsed as:   prefix  /  unit ... (count)  /  tail
 * <p>
 * Works on TOKENS rather than characters: StringTokenizer splits on whitespace
 * (and collapses runs of it), so the spacing differences in machine-generated
 * SQL stop mattering -- no separate normalization step needed. The period scan
 * is identical to the character version; it just compares tokens with equals().
 * <p>
 * Complexity: O(t^2) over the token count t. For very large inputs, use a
 * linear runs algorithm (Main-Lorentz, or the "runs theorem").
 */
public class RunFinder {

    private static final Formatter sqlFormatter = new BasicFormatterImpl();

    /** A maximal tandem repeat: copies of tokens[start, start+period) repeated `count` times. */
    record Run(int start, int period, int count) {
        int covered() { return period * count; }   // tokens spanned by the whole copies
        int end()     { return start + covered(); } // index just past the run
    }

    public static String reformatQuery(String query) {
        // Tokenize on whitespace. StringTokenizer's default delimiters are " \t\n\r\f"
        // and consecutive delimiters never yield empty tokens, so spacing is handled for free.
        List<String> tokens = new ArrayList<>();
        StringTokenizer st = new StringTokenizer(query);
        while (st.hasMoreTokens()) tokens.add(st.nextToken());

        Run best = bestRun(tokens);
        while (best != null) {
            List<String> newTokens = new ArrayList<>();
            newTokens.addAll(tokens.subList(0, best.start()));
            newTokens.addAll(tokens.subList(best.start(), best.start() + best.period()));
            newTokens.add("...*["+best.count()+"]");
            newTokens.addAll(tokens.subList(best.end(), tokens.size()));
            tokens = newTokens;

            best = bestRun(tokens);
            if (best != null && best.count() < 3) {
                best = null;
            }
        }

        String result = String.join(" ", tokens);

        return sqlFormatter.format(result);
    }

    /**
     * For each period p (in tokens), walk the token list finding maximal stretches
     * where tokens[i].equals(tokens[i+p]). A stretch of `matchLen` matching positions
     * spans (matchLen + p) tokens = (matchLen + p)/p whole adjacent copies.
     */
    static Run bestRun(List<String> t) {
        int n = t.size();
        Run best = null;

        for (int p = 1; p <= n / 2; p++) {
            int i = 0;
            while (i + p < n) {
                int j = i;
                while (j + p < n && t.get(j).equals(t.get(j + p))) j++;

                int reps = (j - i + p) / p;           // whole adjacent copies (>= 1)
                if (reps >= 2) {
                    Run cand = new Run(i, p, reps);
                    if (better(cand, best)) best = cand;
                }
                i = (j == i) ? i + 1 : j + 1;          // skip past the stretch we just measured
            }
        }
        return best;
    }

    /**
     * Tie-break order:
     *   1. more tokens covered
     *   2. run that reaches further right  -> picks the clause-aligned framing
     *      ("clause" + ("AND clause") x9) instead of (("clause AND") x9 + "clause")
     *   3. earlier start
     */
    static boolean better(Run a, Run b) {
        if (b == null)                  return true;
        if (a.covered() != b.covered()) return a.covered() > b.covered();
        if (a.end()     != b.end())     return a.end()     > b.end();
        return a.start() < b.start();
    }
}