package com.github.safetytrick.sqlexplorer.commentary;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** Name-level tag attributes; assignments stay path-based rows in {@link Tag}. */
@Data
@Entity
@Table(name = "tag_def")
@EntityListeners(AuditingEntityListener.class)
public class TagDefinition {

    @Id
    private String name;

    /** Display color, e.g. "#f5a623"; chips derive their dim background from it. */
    private String color;

    @CreatedDate
    private Instant createdOn;
}
