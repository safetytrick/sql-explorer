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
