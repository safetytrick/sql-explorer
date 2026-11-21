package com.github.safetytrick.sqlexplorer.auth;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;

public final class AccessTokenAuthentication extends AbstractAuthenticationToken {

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

    public Grants grants() {
        return grants;
    }

    @Override
    public Holder getPrincipal() {
        return holder;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public String getName() {
        return holder.toString();
    }
}
