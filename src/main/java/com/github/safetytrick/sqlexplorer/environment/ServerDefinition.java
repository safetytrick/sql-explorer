package com.github.safetytrick.sqlexplorer.environment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
    /**
     * What the server is for: {@code primary}, {@code replica},
     * {@code analytics}, {@code sampling} — see {@link ServerRole}. None is
     * the default, and means only that nothing samples it.
     */
    private Set<ServerRole> roles = new LinkedHashSet<>();
}
