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

/**
 * A person who authors tags, notes, annotations and the rest.
 *
 * <p>A signed-in user is identified by {@code issuer} + {@code subject}, the
 * pair OIDC guarantees stable and unique; email and name are kept for display
 * and refreshed at each sign-in, never used to look anyone up (sqlx-e13.1).
 * The legacy {@code admin} row has neither: it is every request when sign-in
 * is off, and it keeps everything authored before sign-in existed.
 *
 * <p>Issuer and subject stay out of JSON: this entity is serialized as the
 * author of every tag and note, and those only need a name to show.
 */
@Data
@Entity
@Table(name="users")
@EntityListeners(AuditingEntityListener.class)
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** For display. {@code admin} for the legacy user; may be absent for a signed-in one. */
    private String email;

    /** For display: the {@code name} claim, if the provider sends one. */
    private String name;

    @JsonIgnore
    private String issuer;

    @JsonIgnore
    private String subject;

    /**
     * The {@code groups} claim as of the latest sign-in (sqlx-e13.5): what this
     * user's access tokens are evaluated against, as tokens carry no ID token.
     * Null for a user who has not signed in since tokens existed.
     */
    @JsonIgnore
    @Convert(converter = StringListConverter.class)
    private List<String> groupsSnapshot;

    /** When the ID token the snapshot came from was issued; a snapshot is only replaced by a newer one. */
    @JsonIgnore
    private Instant groupsSeenOn;

    @CreatedDate
    private Instant createdOn;

    @LastModifiedDate
    private Instant lastUpdated;
}
