package com.github.safetytrick.sqlexplorer.environment;

/**
 * JDBC connection details for a {@link DatabaseServer}. These are held by the
 * application itself rather than bound to a Spring-managed DataSource bean so
 * servers can be added, switched, and closed at runtime.
 */
public record ConnectionInfo(String jdbcUrl, String username, String password) {
}
