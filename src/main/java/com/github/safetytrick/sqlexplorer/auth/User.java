package com.github.safetytrick.sqlexplorer.auth;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Data
@Entity
@Table(name="users")
@EntityListeners(AuditingEntityListener.class)
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String email;

    private String name;

    @JsonIgnore
    private String issuer;

    @JsonIgnore
    private String subject;

    @JsonIgnore
    @Convert(converter = StringListConverter.class)
    private List<String> groupsSnapshot;

    @JsonIgnore
    private Instant groupsSeenOn;

    @CreatedDate
    private Instant createdOn;

    @LastModifiedDate
    private Instant lastUpdated;
}
