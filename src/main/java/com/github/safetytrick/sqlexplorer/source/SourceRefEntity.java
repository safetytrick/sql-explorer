package com.github.safetytrick.sqlexplorer.source;

import java.time.Instant;

import javax.persistence.Entity;
import javax.persistence.EntityListeners;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.github.safetytrick.sqlexplorer.auth.User;
import lombok.Data;

@Data
@Entity
@Table(name = "source_ref")
@EntityListeners(AuditingEntityListener.class)
public class SourceRefEntity {

    @Id
    private String name;

    @Enumerated(EnumType.STRING)
    private EnvironmentType environmentType;

    @ManyToOne
    private User creator;

    @CreatedDate
    private Instant createdOn;

}
