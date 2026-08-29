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

/**
 * One real statement, parameters intact, for a normalized query group.
 *
 * <p>The corpus stores normalized SQL — literals stripped, which is what makes
 * merging work and what makes nothing in it runnable. This is the other half.
 *
 * <p>Two rules hold this together and neither is decoration:
 *
 * <ul>
 *   <li><b>{@code normalizedDigest} is derived, never accepted.</b> The caller
 *       submits a statement; the server normalizes it and computes the digest.
 *       Taking a digest from the request would let any statement be attached to
 *       any group with nothing able to detect it.
 *   <li><b>{@code sessionSchema} stores the <em>resolved</em> value.</b> It may
 *       be supplied or inferred, but once resolved it is frozen here, because
 *       the digest was computed from it. Re-inferring later — against a schema
 *       that has since been refreshed — would silently re-key the example.
 * </ul>
 *
 * @see QueryExampleService
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Entity
@Table(name = "query_example")
@EntityListeners(AuditingEntityListener.class)
class QueryExample implements Persistable<UUID> {

    /**
     * UUIDv7, generated application-side — the first non-{@code IDENTITY} key in
     * this schema. Time-ordered, which is the entire reason for v7 here: the
     * retrieval API returns {@code order by id desc} and that is most-recent
     * first only because the key carries its timestamp in the high bits. v7 is
     * big-endian-timestamp-first, so it sorts correctly as raw bytes, which is
     * what {@code binary(16)} compares.
     */
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(columnDefinition = "binary(16)")
    private UUID id;

    /** The statement as submitted, parameters intact. The payload. */
    @Column(name = "query_text", nullable = false)
    private String queryText;

    /** Derived from {@link #queryText} and {@link #sessionSchema}. Never accepted from a caller. */
    @Column(name = "normalized_digest", nullable = false, length = 72)
    private String normalizedDigest;

    /** The scope the digest was derived under — resolved once, then frozen. */
    @Column(name = "session_schema", nullable = false)
    private String sessionSchema;

    /** Whether {@link #sessionSchema} was guessed rather than stated, for later forensics. */
    @Column(name = "schema_inferred", nullable = false)
    private boolean schemaInferred;

    /** Optional note on where the example came from. */
    private String description;

    /**
     * Taken from the active environment rather than the request — inference ran
     * against <em>this</em> environment's schema, so a caller-supplied name that
     * disagreed with it would be a stored lie.
     */
    @Column(name = "environment_name")
    private String environmentName;

    @ManyToOne
    private User author;

    @CreatedDate
    private Instant createdOn;

    /**
     * Hibernate decides insert-versus-update by whether the id is null, and this
     * id never is — it is generated before the entity is saved. Without this the
     * first {@code save()} issues a SELECT and then an UPDATE that matches no
     * row. Every instance this application constructs is new; nothing here loads
     * an example and saves it back.
     */
    @Transient
    @Override
    public boolean isNew() {
        return true;
    }
}
