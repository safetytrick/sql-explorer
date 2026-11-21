package com.github.safetytrick.sqlexplorer.commentary;

import java.time.Instant;
import java.util.Comparator;

import com.github.safetytrick.sqlexplorer.auth.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "annotations")
public class Annotation {

    public static final String INFERRED = "inferred";
    public static final String SAMPLED = "sampled";
    public static final String CONFIRMED = "confirmed";

    public static final String SOURCE_RULE = "rule";
    public static final String SOURCE_SAMPLER = "sampler";
    public static final String SOURCE_PERSON = "person";

    public static final Comparator<Annotation> LATEST = Comparator
        .comparing(Annotation::getRecordedOn, Comparator.nullsFirst(Comparator.naturalOrder()))
        .thenComparing(Annotation::getId, Comparator.nullsFirst(Comparator.naturalOrder()));

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String environmentName;

    private String targetType;
    private String schemaName = "";
    private String tableName = "";
    private String columnName = "";
    private String queryDigest = "";

    private String namespace;

    @Column(name = "ann_key")
    private String key;

    private String value;

    private String confidence;

    private String basis;

    private String sourceKind;

    private String sourceName;

    @ManyToOne
    private User author;

    private Instant recordedOn;

    public void target(TagTarget target) {
        TagTarget normalized = target.normalized();
        this.targetType = normalized.type();
        this.schemaName = normalized.schema();
        this.tableName = normalized.table();
        this.columnName = normalized.column();
        this.queryDigest = normalized.digest();
    }

    public TagTarget getTarget() {
        return new TagTarget(targetType, schemaName, tableName, queryDigest, columnName).normalized();
    }

    public String slot() {
        return namespace + '\u0000' + key;
    }
}
