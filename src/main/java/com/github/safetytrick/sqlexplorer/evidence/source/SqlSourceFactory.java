package com.github.safetytrick.sqlexplorer.evidence.source;

import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.environment.DatabaseServer;
import com.github.safetytrick.sqlexplorer.environment.SourceDefinition;

/**
 * Builds {@link SqlSource}s for a server from a {@link SourceDefinition},
 * selected by {@link #type()}. Replaces the previous global source loaders so
 * that sources are configured per server under
 * {@code environments.<env>.servers.<server>.sources}.
 */
public interface SqlSourceFactory {

    String type();

    Stream<? extends SqlSource> create(DatabaseServer server, SourceDefinition definition);
}
