package com.github.safetytrick.sqlexplorer.examples;

import java.time.Instant;
import java.util.UUID;

import com.github.safetytrick.sqlexplorer.auth.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Data
@EqualsAndHashCode(callSuper = false)
@Entity
@Table(name = "query_example")
@EntityListeners(AuditingEntityListener.class)
class QueryExample implements Persistable<UUID> {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "query_text", nullable = false)
    private String queryText;

    @Column(name = "normalized_digest", nullable = false, length = 72)
    private String normalizedDigest;

    @Column(name = "session_schema", nullable = false)
    private String sessionSchema;

    @Column(name = "schema_inferred", nullable = false)
    private boolean schemaInferred;

    private String description;

    @Column(name = "environment_name")
    private String environmentName;

    @ManyToOne
    private User author;

    @CreatedDate
    private Instant createdOn;

    /**
     * Hibernate decides insert-versus-update by whether the id is null, and this
     * id never is, so without {@link Persistable} the first {@code save()} would
     * issue an UPDATE that matches no row.
     */
    @Transient
    private boolean persisted;

    @jakarta.persistence.PostLoad
    @jakarta.persistence.PostPersist
    void markPersisted() {
        this.persisted = true;
    }

    @Transient
    @Override
    public boolean isNew() {
        return !persisted;
    }
}
