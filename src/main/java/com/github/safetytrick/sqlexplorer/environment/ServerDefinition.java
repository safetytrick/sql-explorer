package com.github.safetytrick.sqlexplorer.environment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ServerDefinition {
    private String jdbcUrl;
    private String username;
    private String password;
    private List<SourceDefinition> sources = new ArrayList<>();
    private Set<ServerRole> roles = new LinkedHashSet<>();
}
