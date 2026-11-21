package com.github.safetytrick.sqlexplorer.topology;

import java.util.List;
import java.util.Map;

public final class ServerComparison {

    private ServerComparison() { }

    public enum Flag {
        ON_PRIMARY,
        SKEWED,
        SINGLE_SERVER,
        PLAN_DIFFERS,
        LATENCY_DIFFERS,
        REPLICA_WRITES
    }

    public enum By { QUERY, TABLE }

    public enum Sort { DIFFERENCE, TIME }

    public record Column(String name, List<String> roles, boolean read, Double windowDays) { }

    public record Cell(long executions, double seconds, double secondsPerDay, double executionsPerDay,
                       double rowsExaminedPerExecution, double msPerExecution, long rowsAffected) { }

    public record Row(String key, String text, String kind, List<Flag> flags, double difference,
                      double secondsPerDay, List<Cell> cells, int queries) { }

    public record Report(String environment, List<String> clusters, String cluster, List<Column> servers, By by,
                         int matched, int compared, Map<Flag, Integer> flagCounts, int unflagged, int total,
                         List<Row> rows, String basis) { }
}
