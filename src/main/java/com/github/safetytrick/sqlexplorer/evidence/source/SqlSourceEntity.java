package com.github.safetytrick.sqlexplorer.evidence.source;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Blob;
import java.sql.SQLException;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.github.safetytrick.sqlexplorer.auth.User;
import com.github.safetytrick.sqlexplorer.evidence.fileloader.AbstractTsvSqlSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SecondaryTable;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Data
@EqualsAndHashCode(callSuper = false)
@Entity
@Table(name="sql_source")
@EntityListeners(AuditingEntityListener.class)
@SecondaryTable(name = "sql_source_content")
public class SqlSourceEntity extends AbstractTsvSqlSource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String sourceRef;
    private String sourceUrl;
    @Column(name="md5_hash")
    private String md5Hash;

    private String origin;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Override
    public com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext.Origin getCaptureOrigin() {
        if (origin == null || origin.isBlank()) {
            return null;
        }
        try {
            return com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext.Origin.valueOf(origin);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Column(name = "server_version")
    private String serverVersion;

    @Column(name = "server_digests_size")
    private Integer serverDigestsSize;

    @Column(name = "server_max_digest_length")
    private Integer serverMaxDigestLength;

    @Override
    @com.fasterxml.jackson.annotation.JsonIgnore
    public java.util.Map<String, String> getCaptureServerContext() {
        java.util.Map<String, String> context = new java.util.LinkedHashMap<>();
        if (serverVersion != null) { context.put("version", serverVersion); }
        if (serverDigestsSize != null) {
            context.put("performance_schema_digests_size", String.valueOf(serverDigestsSize));
        }
        if (serverMaxDigestLength != null) {
            context.put("performance_schema_max_digest_length", String.valueOf(serverMaxDigestLength));
        }
        return context;
    }

    @JsonIgnore
    @Lob
    @Column(table="sql_source_content")
    private Blob content;

    @ManyToOne
    private User creator;

    @CreatedDate
    private Instant createdOn;

    @Override
    public String toString() {
        return "SqlSourceEntity{" +
                "id=" + id +
                ", sourceRef='" + sourceRef + '\'' +
                ", sourceUrl='" + sourceUrl + '\'' +
                '}';
    }

    public String getFilename() {
        return sourceUrl.substring(sourceUrl.lastIndexOf('/') + 1);
    }

    @JsonIgnore
    public Resource getResource() {
        return new AbstractResource() {

            @Override
            public boolean exists() {
                return true;
            }

            @Override
            public String getDescription() {
                return SqlSourceEntity.this.toString();
            }

            @Override
            public InputStream getInputStream() throws IOException {
                try {
                    return content.getBinaryStream();
                } catch (SQLException e) {
                    throw new IOException("Unable to acquire stream", e);
                }
            }

            @Override
            public long lastModified() {
                return createdOn.toEpochMilli();
            }

            @Override
            public long contentLength() throws IOException {
                try {
                    return content.length();
                } catch (SQLException e) {
                    throw new IOException("Unable to determine length", e);
                }
            }
            @Override
            public String getFilename() {
                return SqlSourceEntity.this.getFilename();
            }
        };
    }
}
