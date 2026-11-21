package com.github.safetytrick.sqlexplorer.evidence.source;

import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.environment.DatabaseServer;
import com.github.safetytrick.sqlexplorer.environment.SourceDefinition;

public interface SqlSourceFactory {

    String type();

    Stream<? extends SqlSource> create(String environmentName, DatabaseServer server, SourceDefinition definition);
}
