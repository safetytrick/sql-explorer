package com.github.safetytrick.sqlexplorer.evidence.source;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EntityListeners;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.ManyToOne;
import javax.persistence.SecondaryTable;
import javax.persistence.Table;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Blob;
import java.sql.SQLException;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.github.safetytrick.sqlexplorer.auth.User;
import com.github.safetytrick.sqlexplorer.evidence.fileloader.AbstractTsvSqlSource;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.GenericGenerator;
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
    @GeneratedValue(strategy= GenerationType.AUTO, generator="native")
    @GenericGenerator(name = "native", strategy = "native")
    private Integer id;

    private String sourceRef;
    private String sourceUrl;
    @Column(name="md5_hash")
    private String md5Hash;

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
