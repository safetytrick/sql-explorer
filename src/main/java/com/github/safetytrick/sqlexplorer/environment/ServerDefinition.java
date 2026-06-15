package com.github.safetytrick.sqlexplorer.environment;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * Config for one database server within an environment
 * (under {@code environments.<env>.servers.<name>}): its connection and the
 * SQL sources to ingest from it.
 */
@Getter
@Setter
public class ServerDefinition {
    private String jdbcUrl;
    private String username;
    private String password;
    private List<SourceDefinition> sources = new ArrayList<>();
}
