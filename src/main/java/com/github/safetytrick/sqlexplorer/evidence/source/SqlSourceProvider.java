package com.github.safetytrick.sqlexplorer.evidence.source;

import java.util.stream.Stream;

/**
 * Supplies SQL sources configured for ingestion (e.g. x$statement_analysis on a
 * server, or performance-schema export files). Implementations are gathered by
 * the EnvironmentLoader at startup and on demand, replacing the previous
 * per-loader {@code @PostConstruct} self-triggering.
 */
public interface SqlSourceProvider {

    Stream<? extends SqlSource> sqlSources();
}
