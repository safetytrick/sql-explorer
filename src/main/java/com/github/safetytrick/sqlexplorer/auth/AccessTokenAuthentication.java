package com.github.safetytrick.sqlexplorer.auth;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * A request made with a personal access token (sqlx-e13.5). Its grants are
 * already the intersection of the owner's (as of their latest sign-in) and the
 * token's ceiling, so {@link Authorizations#of} and every URL rule apply to it
 * unchanged.
 */
public final class AccessTokenAuthentication extends AbstractAuthenticationToken {

    /** Who the token belongs to, and which token it is: both go on every audit line. */
    public record Holder(Integer userId, Integer tokenId, String tokenName) implements java.io.Serializable {
        @Override
        public String toString() {
            return "user " + userId + " via token " + tokenId;
        }
    }

    private final Holder holder;
    private final Grants grants;

    AccessTokenAuthentication(Holder holder, Grants grants) {
        super(List.of());
        this.holder = holder;
        this.grants = grants;
        setAuthenticated(true);
    }

    /** What this request may do: min(owner's grants, the token's ceiling). */
    public Grants grants() {
        return grants;
    }

    @Override
    public Holder getPrincipal() {
        return holder;
    }

    /** Never the secret: nothing downstream needs it, and it must not be logged. */
    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public String getName() {
        return holder.toString();
    }
}
