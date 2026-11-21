package com.github.safetytrick.sqlexplorer.topology;

import java.time.LocalDateTime;
import java.util.List;

import com.github.safetytrick.sqlexplorer.model.QueryKind;

public final class Topology {

    private Topology() { }

    public enum Status { READ, STALE, UNREACHABLE, NO_SOURCE, NOT_LOADED }

    public record Share(QueryKind kind, long statements, long executions, double seconds) { }

    public record Load(long statements, long executions, double seconds, Double secondsPerDay,
                       List<Share> shares) { }

    public record Window(LocalDateTime from, LocalDateTime to, double days) { }

    public record Server(String name, List<String> roles, boolean schemaSource, Status status, Load load,
                         Window window, Double shareOfTime, String reason) { }

    public record Cluster(String name, List<Server> servers, Load load) { }

    public record Report(String environment, boolean loaded, boolean cached, List<Cluster> clusters,
                         List<Server> others, Load total, String basis) { }
}
