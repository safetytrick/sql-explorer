package com.github.safetytrick.sqlexplorer.commentary;

import java.time.Instant;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.auth.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Data
@Entity
@Table(name="tags")
@EntityListeners(AuditingEntityListener.class)
public class Tag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String name;

    /**
     * The address, derived by the database from the typed columns below.
     *
     * <p>A STORED GENERATED column, so it cannot disagree with them, and read
     * back after a write rather than assigned — {@code @Generated} is what makes
     * a freshly created tag carry its path in the response the UI consumes.
     * Kept because the list pages look up a few hundred addresses at once, and
     * one indexed {@code path in (...)} beats a tuple match (sqlx-11v).
     */
    @Column(insertable = false, updatable = false)
    @org.hibernate.annotations.Generated(event = {org.hibernate.generator.EventType.INSERT,
        org.hibernate.generator.EventType.UPDATE})
    private String path;

    /** {@code schema}, {@code table} or {@code query} — what this tag addresses. */
    private String type;

    /**
     * The addressed target, in typed columns rather than packed into
     * {@link #path}.
     *
     * <p>EMPTY STRING, NEVER NULL, for the parts a target does not use. MySQL
     * treats NULLs as distinct in a unique index, so a nullable
     * {@code tableName} would let the same schema tag be stored twice.
     */
    private String schemaName = "";
    private String tableName = "";
    private String queryDigest = "";

    /**
     * Which environment this association belongs to.
     *
     * <p>Not nullable, and there is deliberately no "everywhere" value: a tag
     * is about an object in one corpus. See {@link CommentaryEnvironment}.
     */
    private String environmentName;

    @ManyToOne
    private User author;

    @CreatedDate
    private Instant createdOn;

    /**
     * The addressed parts, keyed by the type names that address them.
     *
     * <p>Read from the typed columns now, not parsed back out of the path —
     * which is the whole point of sqlx-11v. Kept on the API because callers look
     * a part up by the tag's own type: {@code getPathElements().get(getType())}
     * is how a query tag's digest is read.
     */
    public Map<String,String> getPathElements() {
        Map<String,String> elements = new java.util.LinkedHashMap<>();
        if (schemaName != null && !schemaName.isEmpty()) {
            elements.put("schema", schemaName);
        }
        if (tableName != null && !tableName.isEmpty()) {
            elements.put("table", tableName);
        }
        if (queryDigest != null && !queryDigest.isEmpty()) {
            elements.put("query", queryDigest);
        }
        return elements;
    }

    /** Point this tag at what a parsed address names. */
    void target(TagPath target) {
        this.type = target.type();
        this.schemaName = target.schema() == null ? "" : target.schema();
        this.tableName = target.table() == null ? "" : target.table();
        this.queryDigest = target.digest() == null ? "" : target.digest();
    }

}
