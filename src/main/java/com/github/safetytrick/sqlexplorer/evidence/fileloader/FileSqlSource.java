package com.github.safetytrick.sqlexplorer.evidence.fileloader;

import java.time.Instant;
import java.util.Objects;

import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import org.springframework.core.io.Resource;

class FileSqlSource extends AbstractTsvSqlSource {

    private final String sourceRef;
    private final Resource resource;
    private final Instant createdOn;

    public FileSqlSource(String sourceRef, Resource resource, Instant createdOn) {
        this.sourceRef = sourceRef;
        this.resource = resource;
        this.createdOn = createdOn;
    }

    public Resource getResource() {
        return resource;
    }

    public Instant getCreatedOn() {
        return createdOn;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SqlSource sqlSource = (SqlSource) o;
        return Objects.equals(sourceRef, sqlSource.getSourceRef()) && Objects.equals(createdOn, sqlSource.getCreatedOn());
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceRef, resource, createdOn);
    }

    @Override
    public String toString() {
        return "SqlSource{" +
            "sourceRef='" + sourceRef + '\'' +
            ", url=" + resource +
            '}';
    }
}
