package com.github.safetytrick.sqlexplorer.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
public class Authorizations {

    private final AuthProperties auth;
    private final GroupGrants groupGrants;

    public Authorizations(AuthProperties auth, GroupGrants groupGrants) {
        this.auth = auth;
        this.groupGrants = groupGrants;
    }

    public Grants current() {
        return of(SecurityContextHolder.getContext().getAuthentication());
    }

    public Grants of(Authentication authentication) {
        if (!auth.oidc()) {
            return Grants.EVERYTHING;
        }
        if (authentication instanceof AccessTokenAuthentication token) {
            return token.grants();
        }
        if (authentication != null && authentication.getPrincipal() instanceof OidcUser user) {
            return groupGrants.of(UserService.groups(user));
        }
        return groupGrants.anonymous();
    }
}
