package com.github.safetytrick.sqlexplorer.profiling;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.github.safetytrick.sqlexplorer.environment.SamplingTarget;
import com.github.safetytrick.sqlexplorer.profiling.ProfilePlan.Method;
import com.github.safetytrick.sqlexplorer.profiling.ProfilePlan.ProfiledColumn;
import com.github.safetytrick.sqlexplorer.profiling.ProfileResult.CharacterCount;
import com.github.safetytrick.sqlexplorer.profiling.ProfileResult.ColumnResult;
import com.github.safetytrick.sqlexplorer.profiling.ProfileResult.ServerIds;
import com.github.safetytrick.sqlexplorer.profiling.ProfileResult.ServerResult;
import com.github.safetytrick.sqlexplorer.profiling.ProfileResult.SignatureResult;
import com.github.safetytrick.sqlexplorer.profiling.ProfileResult.WindowResult;
import com.github.safetytrick.sqlexplorer.profiling.ProfileSql.Statement;
import com.github.safetytrick.sqlexplorer.profiling.ProfileSql.Window;
import com.github.safetytrick.sqlexplorer.profiling.ProfileSql.WindowKind;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs a {@link ProfilePlan} on one sampling server (sqlx-n5j.3): a read-only
 * session with a statement time limit ({@link SamplingTarget#openSession()}),
 * one statement at a time, a pause between them, and nothing but SELECTs over
 * key-bounded windows.
 *
 * <p>Windows are placed so they never overlap: each starts after the last key
 * the previous one read. That keeps every count additive, and when the
 * windows meet end to end — a small table — the run has read every row and
 * says so ({@link ServerResult#complete()}).
 */
@Slf4j
final class ProfileRunner {

    /** Statements kept as text on the record; a full scan issues thousands of the same shape. */
    static final int STATEMENTS_KEPT = 40;

    private final ProfilePlan plan;
    private final ProfileSql sql;
    private final BooleanSupplier cancelled;
    private final Runnable onStatement;

    private final List<WindowResult> windows = new ArrayList<>();
    private final List<String> statements = new ArrayList<>();
    private final List<Tally> tallies;
    private int statementCount;
    private long rowsRead;

    ProfileRunner(ProfilePlan plan, BooleanSupplier cancelled, Runnable onStatement) {
        this.plan = plan;
        this.sql = new ProfileSql(plan);
        this.cancelled = cancelled;
        this.onStatement = onStatement;
        this.tallies = plan.columns().stream().map(Tally::new).toList();
    }

    ServerResult run(String cluster, SamplingTarget target, Long rowsEstimate) {
        String server = target.server().getName();
        String status = "done";
        String reason = null;
        boolean complete = false;
        try (SamplingTarget.SamplingSession session = target.openSession()) {
            Connection connection = session.connection();
            complete = switch (plan.method()) {
                case WINDOWS -> windows(connection);
                case ENDS -> ends(connection);
                case FULL_SCAN -> fullScan(connection);
                case NONE -> false;
            };
            if (cancelled.getAsBoolean()) {
                status = "cancelled";
                complete = false;
            }
        } catch (SQLException | RuntimeException e) {
            log.warn("Profile of {}.{} on {} failed: {}", plan.schema(), plan.table(), server, e.getMessage());
            status = "failed";
            reason = e.getMessage();
            complete = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = "cancelled";
        }
        if (plan.method() == Method.WINDOWS) {
            // read head, tail, then between: listed in key order
            windows.sort(java.util.Comparator.comparing(w -> w.from() == null ? Long.MAX_VALUE
                : Long.parseLong(w.from())));
        }
        boolean windowFailed = windows.stream().anyMatch(w -> w.error() != null);
        boolean whole = complete && !windowFailed;
        return new ServerResult(cluster, server, rowsEstimate, status, reason, whole, coverage(whole),
            List.copyOf(windows), rowsRead, statementCount, List.copyOf(statements),
            tallies.stream().map(t -> t.result(server, whole)).toList());
    }

    // ── methods ──

    /**
     * The first rows, then the last rows after them, then ten windows spaced
     * evenly between the two, each starting after the last key read and
     * stopping short of the tail. Nothing is read twice; when the windows meet
     * end to end, every row has been read.
     */
    private boolean windows(Connection connection) throws SQLException, InterruptedException {
        Statement bounds = sql.bounds();
        Object min;
        try (PreparedStatement statement = prepare(connection, bounds); ResultSet rows = statement.executeQuery()) {
            rows.next();
            min = rows.getObject("min_key");
        }
        if (min == null) {
            return true;
        }
        WindowRead head = read(connection, new Window(WindowKind.HEAD, null, ProfilePlan.HEAD_ROWS, "first rows"));
        if (head == null || head.rows < ProfilePlan.HEAD_ROWS) {
            return head != null;
        }
        WindowRead tail = cancelled.getAsBoolean() ? null
            : read(connection, new Window(WindowKind.TAIL, head.maxKey, ProfilePlan.TAIL_ROWS, "last rows"));
        if (tail == null) {
            return false;
        }
        if (tail.rows < ProfilePlan.TAIL_ROWS) {
            return true;
        }
        long low = head.maxKey;
        long high = tail.minKey;
        long last = low;
        boolean contiguous = true;
        boolean met = high <= low + 1;
        for (int i = 1; i <= ProfilePlan.MIDDLE_WINDOWS && !met && !cancelled.getAsBoolean(); i++) {
            long target = low + (long) ((double) (high - low) * i / (ProfilePlan.MIDDLE_WINDOWS + 1));
            long start = Math.max(target, last + 1);
            if (start >= high) {
                break;
            }
            contiguous &= target <= last + 1;
            WindowRead window = read(connection, new Window(WindowKind.FROM, start, high, ProfilePlan.MIDDLE_ROWS,
                "window " + i + " of " + ProfilePlan.MIDDLE_WINDOWS));
            if (window == null) {
                contiguous = false;
                continue;
            }
            if (window.rows > 0) {
                last = window.maxKey;
            }
            // fewer rows than asked for: nothing more before the tail
            met = window.rows < ProfilePlan.MIDDLE_ROWS;
        }
        return contiguous && met && !cancelled.getAsBoolean();
    }

    /** Head and tail only. They can overlap on a table smaller than both together, which the coverage says. */
    private boolean ends(Connection connection) throws SQLException, InterruptedException {
        WindowRead head = read(connection, new Window(WindowKind.HEAD, null, ProfilePlan.HEAD_ROWS, "first rows"));
        if (head == null || head.rows < ProfilePlan.HEAD_ROWS) {
            return head != null;
        }
        if (!cancelled.getAsBoolean()) {
            read(connection, new Window(WindowKind.TAIL, null, ProfilePlan.TAIL_ROWS, "last rows"));
        }
        return false;
    }

    /** Every row in key order, a chunk at a time, each chunk after the last key read. */
    private boolean fullScan(Connection connection) throws SQLException, InterruptedException {
        Object after = null;
        int chunk = 1;
        while (!cancelled.getAsBoolean()) {
            WindowRead read = read(connection, new Window(WindowKind.AFTER, after, ProfilePlan.FULL_SCAN_CHUNK,
                "chunk " + chunk++));
            if (read == null) {
                return false;
            }
            if (read.rows < ProfilePlan.FULL_SCAN_CHUNK) {
                return true;
            }
            after = read.maxKeyObject;
        }
        return false;
    }

    // ── one window ──

    private record WindowRead(long rows, long minKey, long maxKey, Object maxKeyObject) { }

    /** One window's counts, then the characters behind any listed signature it matched. Null when it failed. */
    private WindowRead read(Connection connection, Window window) throws InterruptedException {
        Statement aggregate = sql.aggregate(window);
        long started = System.nanoTime();
        long rows;
        Object minKey;
        Object maxKey;
        Map<Integer, EnumMap<Signature, Long>> hits = new LinkedHashMap<>();
        try (PreparedStatement statement = prepare(connection, aggregate); ResultSet result = statement.executeQuery()) {
            result.next();
            rows = result.getLong("n_rows");
            minKey = result.getObject("min_key");
            maxKey = result.getObject("max_key");
            for (int i = 0; i < tallies.size(); i++) {
                hits.put(i, tallies.get(i).add(result, "c" + i));
            }
        } catch (SQLException e) {
            windows.add(new WindowResult(window.label(), text(window.bound()), null, 0, millis(started),
                e.getMessage()));
            return null;
        }
        rowsRead += rows;
        windows.add(new WindowResult(window.label(), text(minKey), text(maxKey), rows, millis(started), null));
        for (int i = 0; i < tallies.size(); i++) {
            List<Signature> listed = hits.get(i).entrySet().stream()
                .filter(e -> e.getValue() > 0 && e.getKey().listsCharacters()).map(Map.Entry::getKey).toList();
            if (!listed.isEmpty() && !cancelled.getAsBoolean()) {
                characters(connection, window, i, listed);
            }
        }
        long min = minKey instanceof Number number ? number.longValue() : 0;
        long max = maxKey instanceof Number number ? number.longValue() : 0;
        return new WindowRead(rows, min, max, maxKey);
    }

    private void characters(Connection connection, Window window, int column, List<Signature> signatures)
        throws InterruptedException {
        Statement statement = sql.characters(window, plan.columns().get(column), signatures);
        try (PreparedStatement prepared = prepare(connection, statement); ResultSet result = prepared.executeQuery()) {
            while (result.next()) {
                tallies.get(column).character(Signature.valueOf(result.getString("sig")),
                    result.getString("matched"), result.getLong("n_rows"));
            }
        } catch (SQLException e) {
            // the counts stand without the characters behind them
            log.warn("Listing characters of {} failed: {}", plan.columns().get(column).name(), e.getMessage());
        }
    }

    /** Every statement goes through here: kept for the record, counted, paced. */
    private PreparedStatement prepare(Connection connection, Statement statement)
        throws SQLException, InterruptedException {
        if (statementCount > 0) {
            Thread.sleep(ProfilePlan.PAUSE.toMillis());
        }
        statementCount++;
        if (statements.size() < STATEMENTS_KEPT) {
            statements.add(statement.display());
        }
        onStatement.run();
        PreparedStatement prepared = connection.prepareStatement(statement.sql());
        try {
            for (int i = 0; i < statement.parameters().size(); i++) {
                prepared.setObject(i + 1, statement.parameters().get(i));
            }
        } catch (SQLException | RuntimeException e) {
            prepared.close();
            throw e;
        }
        return prepared;
    }

    private String coverage(boolean complete) {
        long ok = windows.stream().filter(w -> w.error() == null).count();
        String read = String.format(java.util.Locale.ROOT, "%,d", rowsRead) + " rows in " + ok + " window" + (ok == 1 ? "" : "s");
        if (complete) {
            return "every row read (" + read + ")";
        }
        if (plan.method() == Method.ENDS) {
            return read + ", the first and last rows by key only; the two can overlap on a small table";
        }
        return read + " across the key range; a sample, not every row";
    }

    private static long millis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    /** One column's running totals across windows. */
    private static final class Tally {
        private final ProfiledColumn column;
        private long rows;
        private long nulls;
        private long nonAscii;
        private Long maxBytes;
        private long distinctAtLeast;
        private final EnumMap<Signature, Long> signatureRows = new EnumMap<>(Signature.class);
        private final EnumMap<Signature, List<String>> ids = new EnumMap<>(Signature.class);
        private final EnumMap<Signature, Map<String, Long>> characters = new EnumMap<>(Signature.class);

        Tally(ProfiledColumn column) {
            this.column = column;
            column.signatures().forEach(s -> {
                signatureRows.put(s, 0L);
                ids.put(s, new ArrayList<>());
                characters.put(s, new LinkedHashMap<>());
            });
        }

        /** Adds one window's figures; answers the window's rows per signature. */
        EnumMap<Signature, Long> add(ResultSet result, String prefix) throws SQLException {
            long windowRows = result.getLong("n_rows");
            rows += windowRows;
            nulls += result.getLong(prefix + "_nulls");
            long bytes = result.getLong(prefix + "_bytes");
            if (!result.wasNull()) {
                maxBytes = maxBytes == null ? bytes : Math.max(maxBytes, bytes);
            }
            distinctAtLeast = Math.max(distinctAtLeast, result.getLong(prefix + "_distinct"));
            nonAscii += result.getLong(prefix + "_non_ascii");
            EnumMap<Signature, Long> window = new EnumMap<>(Signature.class);
            for (Signature signature : column.signatures()) {
                long matched = result.getLong(prefix + "_s" + signature.ordinal());
                window.put(signature, matched);
                signatureRows.merge(signature, matched, Long::sum);
                List<String> kept = ids.get(signature);
                String keys = result.getString(prefix + "_k" + signature.ordinal());
                if (keys != null && !keys.isEmpty() && kept.size() < ProfilePlan.IDS_PER_SIGNATURE) {
                    List<String> parsed = new ArrayList<>(Arrays.asList(keys.split(",")));
                    if (keys.length() >= 1_000) {
                        // GROUP_CONCAT stops at group_concat_max_len (1024 by default): the last key may be cut
                        parsed.removeLast();
                    }
                    for (String key : parsed) {
                        if (kept.size() < ProfilePlan.IDS_PER_SIGNATURE) {
                            kept.add(key);
                        }
                    }
                }
            }
            return window;
        }

        void character(Signature signature, String matched, long count) {
            if (matched != null && characters.containsKey(signature)) {
                characters.get(signature).merge(matched, count, Long::sum);
            }
        }

        ColumnResult result(String server, boolean complete) {
            List<SignatureResult> signatures = column.signatures().stream().map(s -> new SignatureResult(s,
                s.label(), s.explanation(), s.heuristic(), s.informational(), signatureRows.get(s),
                ids.get(s).isEmpty() ? List.of() : List.of(new ServerIds(server, inKeyOrder(ids.get(s)))),
                characterCounts(s, characters.get(s)))).toList();
            return new ColumnResult(column.name(), column.type(), column.characterSet(), column.collation(), rows,
                nulls, nonAscii, maxBytes, distinctAtLeast, signatures,
                Verdicts.of(column.characterSet(), rows, nonAscii, signatures, complete));
        }
    }

    /** Keys as read are in window order (head, tail, then between); listed in key order. */
    static List<String> inKeyOrder(List<String> keys) {
        boolean numeric = keys.stream().allMatch(k -> k.matches("-?\\d{1,18}"));
        return numeric ? keys.stream().sorted(java.util.Comparator.comparingLong(Long::parseLong)).toList()
            : List.copyOf(keys);
    }

    static List<CharacterCount> characterCounts(Signature signature, Map<String, Long> counts) {
        return counts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .map(e -> new CharacterCount(e.getKey(), codePoints(e.getKey()), signature.intended(e.getKey()),
                e.getValue()))
            .toList();
    }

    static String codePoints(String text) {
        return text.codePoints().mapToObj(c -> String.format("U+%04X", c))
            .reduce((a, b) -> a + " " + b).orElse("");
    }
}
