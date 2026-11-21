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

    @Column(insertable = false, updatable = false)
    @org.hibernate.annotations.Generated(event = {org.hibernate.generator.EventType.INSERT,
        org.hibernate.generator.EventType.UPDATE})
    private String path;

    private String type;

    /**
     * Empty, never null: MySQL treats NULLs as distinct in a unique index, so a
     * nullable {@code tableName} would let the same schema tag be stored twice.
     */
    private String schemaName = "";
    private String tableName = "";
    private String queryDigest = "";
    private String columnName = "";

    private String environmentName;

    @ManyToOne
    private User author;

    @CreatedDate
    private Instant createdOn;

    public Map<String,String> getPathElements() {
        Map<String,String> elements = new java.util.LinkedHashMap<>();
        if (schemaName != null && !schemaName.isEmpty()) {
            elements.put("schema", schemaName);
        }
        if (tableName != null && !tableName.isEmpty()) {
            elements.put("table", tableName);
        }
        if (columnName != null && !columnName.isEmpty()) {
            elements.put("column", columnName);
        }
        if (queryDigest != null && !queryDigest.isEmpty()) {
            elements.put("query", queryDigest);
        }
        return elements;
    }

    public void target(TagTarget target) {
        TagTarget normalized = target.normalized();
        this.type = normalized.type();
        this.schemaName = normalized.schema();
        this.tableName = normalized.table();
        this.queryDigest = normalized.digest();
        this.columnName = normalized.column();
    }

    public TagTarget getTarget() {
        return new TagTarget(type, schemaName, tableName, queryDigest, columnName).normalized();
    }

}
