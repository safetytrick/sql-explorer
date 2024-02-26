package com.github.safetytrick.sqlexplorer.evidence.source;

import java.time.Instant;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonIgnore;

public interface SqlSource {
    @JsonIgnore
    Stream<SqlInput> getSql();
    Instant getCreatedOn();
    String getSourceRef();
}
