package com.github.safetytrick.sqlexplorer.evidence.source;

import java.time.Instant;

import com.github.safetytrick.sqlexplorer.auth.User;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Data
@Entity
@Table(name = "source_ref")
@EntityListeners(AuditingEntityListener.class)
class SourceRefEntity {

    @Id
    private String name;

    private String environmentName;

    @ManyToOne
    private User creator;

    @CreatedDate
    private Instant createdOn;

}
