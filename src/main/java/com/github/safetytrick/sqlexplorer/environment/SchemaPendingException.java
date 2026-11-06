package com.github.safetytrick.sqlexplorer.environment;

/**
 * A server's schema was asked for while someone else is reading it, or while
 * its environment is being pulled (sqlx-o7x).
 *
 * <p>Thrown instead of waiting. Reading a schema from an unreachable server
 * takes the whole connect timeout, and a request that waited on it held up
 * every page behind it. Not a failure and not remembered: the cluster serves
 * its snapshot meanwhile, and the next call after the read finishes gets the
 * schema it produced.
 */
public class SchemaPendingException extends IllegalStateException {

    public SchemaPendingException(String server, String why) {
        super("the schema of server " + server + " is not read yet because " + why
            + "; the snapshot is served meanwhile");
    }
}
