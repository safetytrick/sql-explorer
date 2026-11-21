package com.github.safetytrick.sqlexplorer.auth;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "access_tokens")
public class AccessToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private Integer userId;

    private String name;

    private String tokenHash;

    private String tokenPrefix;

    private String ceiling;

    private Instant createdOn;

    private Instant expiresOn;

    private Instant lastUsedOn;

    private Instant revokedOn;
}
