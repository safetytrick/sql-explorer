package com.github.safetytrick.sqlexplorer.environment;

/**
 * A connection was asked of a server whose environment is served from its
 * cache (sqlx-nbi.4).
 *
 * <p>Thrown instead of creating a pool, so "a fresh cache connects to
 * nothing" holds for every path, not only the ones someone remembered to
 * check. Pulling the environment takes it live and lifts the refusal.
 */
public class ServerOfflineException extends IllegalStateException {

    public ServerOfflineException(String environment, String server) {
        super("environment " + environment + " is served from its cache, so server " + server
            + " is not connected; pull the environment to go live");
    }
}
