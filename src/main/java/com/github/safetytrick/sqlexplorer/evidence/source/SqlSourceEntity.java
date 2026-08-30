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

    /**
     * What the file is, relative to {@code sys.x$statement_analysis} — every
     * corpus originates there, but a TSV export is one hop away and a parquet
     * copy two. Stored as the name of {@code CaptureContext.Origin}.
     */
    private String origin;

    /**
     * When the rows were read off the source server.
     *
     * <p>Not when the file was uploaded — {@code createdOn} is that, and the
     * two can be months apart. This is the one that makes the server settings
     * below mean anything, and the one gap detection orders samples by.
     */
    @Column(name = "captured_at")
    private Instant capturedAt;

    /**
     * What the source server looked like at capture time.
     *
     * <p>Null means unknown, which is the honest reading for anything uploaded
     * before these existed. They must never be backfilled from a live
     * connection: {@code performance_schema_digests_size} and
     * {@code max_digest_length} applied on <em>that</em> server at
     * <em>that</em> moment, and today's values describe neither (sqlx-sy5).
     */
    @Column(name = "server_version")
    private String serverVersion;

    @Column(name = "server_digests_size")
    private Integer serverDigestsSize;

    @Column(name = "server_max_digest_length")
    private Integer serverMaxDigestLength;

    /**
     * The uploader-supplied values, in the shape every source reports them.
     * Absent entries mean unknown — an export uploaded before this was
     * required has none, and inventing them would be the error this guards.
     */
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
