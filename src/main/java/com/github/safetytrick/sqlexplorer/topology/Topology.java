package com.github.safetytrick.sqlexplorer.topology;

import java.time.LocalDateTime;
import java.util.List;

import com.github.safetytrick.sqlexplorer.model.QueryKind;

/**
 * An environment's servers as its config arranges them — clusters, each a
 * primary and its replicas — with what each server's own Performance Schema
 * recorded (sqlx-a9h).
 *
 * <p>Every other summary merges an environment's servers into one corpus.
 * This one deliberately does not: a statement that ran on the primary and on
 * two replicas is counted on each, where it ran, because the question is how
 * the load splits between them.
 *
 * <p>The figures are cumulative since each server's Performance Schema was
 * last reset (normally its restart), and every server has its own window. A
 * replica restarted last week and a primary up for a year are not comparable
 * on totals; {@link Load#secondsPerDay()} divides each by its own window.
 */
public final class Topology {

    private Topology() { }

    /**
     * What happened when the server was last read.
     * <ul>
     *   <li>{@code READ} — its statements are from the latest load or its capture.</li>
     *   <li>{@code STALE} — the latest load could not read it; the figures are its previous capture.</li>
     *   <li>{@code UNREACHABLE} — it could not be read and there is nothing earlier to show.</li>
     *   <li>{@code NO_SOURCE} — config gives it no statement source, so nothing is captured from it.</li>
     *   <li>{@code NOT_LOADED} — the environment has not been loaded.</li>
     * </ul>
     */
    public enum Status { READ, STALE, UNREACHABLE, NO_SOURCE, NOT_LOADED }

    /**
     * One kind of statement on one server.
     *
     * @param statements distinct digests
     * @param executions times they ran
     * @param seconds    their total execution time
     */
    public record Share(QueryKind kind, long statements, long executions, double seconds) { }

    /**
     * What one server (or a cluster, or the environment) recorded.
     *
     * @param shares        reads, writes and other, always all three, in that order
     * @param secondsPerDay total seconds over the observed window, floored at a
     *                      day; null when the window is unknown
     */
    public record Load(long statements, long executions, double seconds, Double secondsPerDay,
                       List<Share> shares) { }

    /**
     * @param from earliest first-seen across the server's statements
     * @param to   latest last-seen
     * @param days {@code to - from} in days, fractional
     */
    public record Window(LocalDateTime from, LocalDateTime to, double days) { }

    /**
     * @param roles         as config declares them, lowercase
     * @param schemaSource  whether the cluster reads its schema from this server
     * @param load          null unless the status carries figures
     * @param shareOfTime   this server's seconds over the environment's, 0–1;
     *                      null when there are none
     * @param reason        why a server has no figures, or why they are old
     */
    public record Server(String name, List<String> roles, boolean schemaSource, Status status, Load load,
                         Window window, Double shareOfTime, String reason) { }

    public record Cluster(String name, List<Server> servers, Load load) { }

    /**
     * @param others sources that name no configured server (an imported file,
     *               say): counted in the total, shown apart from the clusters
     */
    public record Report(String environment, boolean loaded, boolean cached, List<Cluster> clusters,
                         List<Server> others, Load total, String basis) { }
}
