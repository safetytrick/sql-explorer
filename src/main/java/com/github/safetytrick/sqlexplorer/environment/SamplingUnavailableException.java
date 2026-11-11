package com.github.safetytrick.sqlexplorer.environment;

/**
 * An environment has no server SQL Explorer may sample right now, and the
 * message says why (sqlx-n5j.4). A feature that samples shows that message
 * rather than hiding itself, so the reason is visible where the feature is.
 */
public class SamplingUnavailableException extends IllegalStateException {

    public SamplingUnavailableException(String message) {
        super(message);
    }
}
