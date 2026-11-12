package com.github.safetytrick.sqlexplorer.auth;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * A personal access token (sqlx-e13.5): a secret an MCP or API client sends
 * as {@code Authorization: Bearer}, acting as its owner but never beyond its
 * {@link #ceiling}.
 *
 * <p>The secret itself is not kept, only its SHA-256 ({@link #tokenHash}), so
 * it is shown once, when it is made, and a copy of this table lets nobody in.
 * Never serialized as it is: {@link AccessTokenResource} answers with a view
 * that leaves the hash out.
 */
@Data
@Entity
@Table(name = "access_tokens")
public class AccessToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** The owner: whose grants the token acts within, and who it writes as. */
    private Integer userId;

    /** What the owner called it, to tell their tokens apart: "laptop MCP". */
    private String name;

    /** Hex SHA-256 of the whole secret, prefix included. */
    private String tokenHash;

    /** The secret's first characters, shown in lists; too short to be worth anything. */
    private String tokenPrefix;

    /** The most it may do, as grants in the groups syntax, comma separated: {@code viewer:prod,runner:prod/app}. */
    private String ceiling;

    private Instant createdOn;

    private Instant expiresOn;

    /** Updated at most about once a minute, so using a token is not a write per request. */
    private Instant lastUsedOn;

    private Instant revokedOn;
}
