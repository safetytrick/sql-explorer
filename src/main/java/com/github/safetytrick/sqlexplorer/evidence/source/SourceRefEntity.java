package com.github.safetytrick.sqlexplorer.evidence.source;

import javax.persistence.Entity;
import javax.persistence.EntityListeners;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import java.time.Instant;

import com.github.safetytrick.sqlexplorer.auth.User;
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

    @Enumerated(EnumType.STRING)
    private EnvironmentType environmentType;

    @ManyToOne
    private User creator;

    @CreatedDate
    private Instant createdOn;

}
