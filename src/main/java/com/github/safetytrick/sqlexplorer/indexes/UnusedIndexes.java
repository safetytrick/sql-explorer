package com.github.safetytrick.sqlexplorer.indexes;

import java.time.Instant;
import java.util.List;

import com.github.safetytrick.sqlexplorer.coverage.Coverage;

public final class UnusedIndexes {

    private UnusedIndexes() {
    }

    public record Index(String schema, String table, String name) {
    }

    public record Connection(String connection, Coverage.Verdict verdict, String problem, String note,
                             Long upSeconds, int unusedHere, int readHere) {
    }

    public record Report(String environment, List<Connection> connections, boolean complete,
                         List<Index> unused, String basis, Instant readAt, long readMillis, long minimumDays) {
    }
}
