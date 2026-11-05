package com.github.safetytrick.sqlexplorer.graph;

import java.time.Instant;

import com.github.safetytrick.sqlexplorer.auth.User;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One item's place in the graph's grid layout, arranged by hand (sqlx-aoy.15).
 *
 * <p>Keyed by schema name and shared by every user and every environment, so
 * a schema arranged once keeps its arrangement wherever that schema appears.
 * That is deliberately unlike tags and notes, which belong to an environment.
 * See {@link GraphLayoutItem} for what each field means.
 */
@Data
@Entity
@Table(name = "graph_layout")
@EntityListeners(AuditingEntityListener.class)
class GraphLayoutEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String kind;
    private String schemaName;
    private String itemName;
    private Integer slotCol;
    private Integer slotRow;
    private boolean aggregateOverride;
    private String aggregateParent;

    @ManyToOne
    private User updatedBy;

    @LastModifiedDate
    private Instant updatedOn;
}
