package com.github.safetytrick.sqlexplorer.environment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ClusterDefinition {
    private List<String> schemaFrom = new ArrayList<>();
    private Map<String, ServerDefinition> servers = new LinkedHashMap<>();
}
