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

/**
 * A fact recorded about an object, with where it came from (sqlx-n5j.13).
 *
 * <p>NOT A TAG. A tag is human vocabulary: a label, present or absent. An
 * annotation is a record — what was stated ({@link #namespace}, {@link #key},
 * {@link #value}), how sure ({@link #confidence}), why ({@link #basis}) and who
 * said so ({@link #sourceKind}, {@link #sourceName}, {@link #author}). Keeping
 * them apart is what lets every tag stay a person's word and every annotation
 * carry its provenance.
 *
 * <p>APPEND-ONLY. A confirmation or a correction is a new row; the latest row
 * for one (target, namespace, key) is the current answer ({@link #LATEST}), and
 * the earlier ones are how it came to be believed. Nothing updates a row.
 *
 * <p>Addressed like a tag ({@link TagTarget}, columns included) plus the
 * environment itself, and scoped to one environment the same way
 * ({@link CommentaryEnvironment}). Lives in the explorer database only: nothing
 * here reads a monitored server.
 */
@Data
@Entity
@Table(name = "annotations")
public class Annotation {

    public static final String INFERRED = "inferred";
    public static final String SAMPLED = "sampled";
    public static final String CONFIRMED = "confirmed";

    /** Written by a rule in this code base; {@link #sourceName} names the rule. */
    public static final String SOURCE_RULE = "rule";
    /** Written by a sampler reading data; {@link #sourceName} names it. */
    public static final String SOURCE_SAMPLER = "sampler";
    /** Written by a person, who is the {@link #author}. */
    public static final String SOURCE_PERSON = "person";

    /** Later beats earlier: by recorded time, then by insertion order. */
    public static final Comparator<Annotation> LATEST = Comparator
        .comparing(Annotation::getRecordedOn, Comparator.nullsFirst(Comparator.naturalOrder()))
        .thenComparing(Annotation::getId, Comparator.nullsFirst(Comparator.naturalOrder()));

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String environmentName;

    /** The target, in the same typed columns a tag uses; '' for parts a kind does not use. */
    private String targetType;
    private String schemaName = "";
    private String tableName = "";
    private String columnName = "";
    private String queryDigest = "";

    /** Whose vocabulary the key belongs to, e.g. {@code schema-role} or {@code generator}. */
    private String namespace;

    @Column(name = "ann_key")
    private String key;

    private String value;

    /** {@link #INFERRED}, {@link #SAMPLED} or {@link #CONFIRMED}. */
    private String confidence;

    /** Why: the rule's reasoning, or the sample windows and method. Free text or JSON. */
    private String basis;

    /** {@link #SOURCE_RULE}, {@link #SOURCE_SAMPLER} or {@link #SOURCE_PERSON}. */
    private String sourceKind;

    /** The rule or sampler, by name. Null for a person, who is {@link #author}. */
    private String sourceName;

    /** The person, when {@link #sourceKind} is {@link #SOURCE_PERSON}. */
    @ManyToOne
    private User author;

    private Instant recordedOn;

    /** Point this annotation at a target. */
    public void target(TagTarget target) {
        TagTarget normalized = target.normalized();
        this.targetType = normalized.type();
        this.schemaName = normalized.schema();
        this.tableName = normalized.table();
        this.columnName = normalized.column();
        this.queryDigest = normalized.digest();
    }

    /** What this annotation is about. */
    public TagTarget getTarget() {
        return new TagTarget(targetType, schemaName, tableName, queryDigest, columnName).normalized();
    }

    /** The (namespace, key) this row answers, which is what "latest wins" groups by. */
    public String slot() {
        return namespace + '\u0000' + key;
    }
}
