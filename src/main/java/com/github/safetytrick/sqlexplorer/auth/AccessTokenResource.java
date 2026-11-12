package com.github.safetytrick.sqlexplorer.auth;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Personal access tokens, {@code /tokens} (sqlx-e13.5). Global: a token is a
 * person's, not an environment's, and its ceiling names environments itself.
 *
 * <p>Only from a browser session. A request made with a token is refused
 * (by BearerTokenFilter, SecurityConfig, and here), so a token can never make
 * another, widen itself or put off its own expiry.
 *
 * <p>Everyone manages their own; an administrator may also list and revoke
 * anyone's ({@code ?all=true}), for when a token leaks and its owner is not
 * around. Nobody, administrators included, can see a secret after it is made.
 */
@Tag(name = "User")
@RestController
class AccessTokenResource {

    private final AccessTokenService tokens;
    private final UserService users;
    private final UserRepository userRepository;
    private final Authorizations authorizations;
    private final AuthProperties auth;

    AccessTokenResource(AccessTokenService tokens, UserService users, UserRepository userRepository,
                        Authorizations authorizations, AuthProperties auth) {
        this.tokens = tokens;
        this.users = users;
        this.userRepository = userRepository;
        this.authorizations = authorizations;
        this.auth = auth;
    }

    /**
     * One token as listed: never its hash, let alone its secret.
     *
     * @param status {@code active}, {@code expired} or {@code revoked}
     * @param owner  who it belongs to; only filled in an administrator's
     *               listing of everyone's
     */
    record TokenView(Integer id, String name, String prefix, List<String> ceiling, Instant createdOn,
                     Instant expiresOn, Instant lastUsedOn, Instant revokedOn, String status, Owner owner) {}

    record Owner(Integer id, String email, String name) {}

    /**
     * The listing, with what the page needs to offer a new one.
     *
     * @param enabled         false with sign-in off, where there is nothing to
     *                        authenticate and {@code /mcp} is open anyway
     * @param grants          the caller's own grants, as tokens see them: from
     *                        their latest sign-in
     * @param ceilingOptions  what a new token's ceiling may be chosen from
     * @param maxLifetimeDays the longest a new token may live
     * @param admin           whether {@code ?all=true} is open to the caller
     */
    record TokenList(boolean enabled, List<TokenView> tokens, List<String> grants, List<String> ceilingOptions,
                     long maxLifetimeDays, boolean admin) {}

    /** @param expiresInDays at most the maximum; absent means the maximum */
    record CreateRequest(String name, List<String> ceiling, Integer expiresInDays) {}

    /** The one time the secret is shown. */
    record CreatedView(TokenView token, String secret) {}

    @GetMapping("/tokens")
    public TokenList list(@RequestParam(name = "all", defaultValue = "false") boolean all) {
        if (!auth.oidc()) {
            return new TokenList(false, List.of(), List.of(), List.of(), tokens.maxLifetimeDays(), false);
        }
        User me = me();
        boolean admin = authorizations.current().admin();
        if (all && !admin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an administrator can list everyone's tokens.");
        }
        Grants own = tokens.ownerGrants(me);
        List<TokenView> views;
        if (all) {
            Map<Integer, User> owners = new HashMap<>();
            StreamSupport.stream(userRepository.findAll().spliterator(), false).forEach(u -> owners.put(u.getId(), u));
            views = tokens.listAll().stream().map(t -> view(t, owners.get(t.getUserId()))).toList();
        } else {
            views = tokens.listOwn(me).stream().map(t -> view(t, null)).toList();
        }
        return new TokenList(true, views, own.grants().stream().map(Grant::toString).toList(),
            tokens.ceilingOptions(own), tokens.maxLifetimeDays(), admin);
    }

    @PostMapping("/tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedView create(@RequestBody CreateRequest request) {
        if (!auth.oidc()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Access tokens need sign-in, and sign-in is not "
                + "configured here (sql-explorer.auth.mode is none): the API and /mcp are open without one.");
        }
        User me = me();
        try {
            AccessTokenService.Created created = tokens.create(me, request.name(), request.ceiling(), request.expiresInDays());
            return new CreatedView(view(created.token(), null), created.secret());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** Revokes it; the row stays, so the list still shows what it was and when it stopped. */
    @DeleteMapping("/tokens/{id}")
    public TokenView revoke(@PathVariable Integer id) {
        if (!auth.oidc()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        User me = me();
        AccessToken token = tokens.find(id)
            .filter(t -> t.getUserId().equals(me.getId()) || authorizations.current().admin())
            // someone else's token is not found, rather than forbidden: its existence is not theirs to learn
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such token."));
        return view(tokens.revoke(token, me), null);
    }

    /**
     * The signed-in user, from a browser session only. Their groups snapshot is
     * refreshed from the session if the session is the newer sign-in, which
     * also covers a session that began before snapshots were kept.
     */
    private User me() {
        if (users.accessToken().isPresent()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "An access token cannot manage access tokens.");
        }
        OidcUser oidc = users.signedInPrincipal()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to manage access tokens."));
        return users.signedIn(oidc);
    }

    /**
     * Refusals carry their reason as {@code message}: the page shows it as
     * written ("beyond your own grants (viewer:prod)"), and Boot's error page
     * leaves messages out.
     */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Object>> refused(ResponseStatusException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", e.getStatusCode().value());
        body.put("error", e.getStatusCode() instanceof HttpStatus status ? status.getReasonPhrase() : "Error");
        body.put("message", e.getReason());
        return ResponseEntity.status(e.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private TokenView view(AccessToken token, User owner) {
        String status = tokens.status(token);
        List<String> ceiling = AccessTokenService.storedCeiling(token).grants().stream()
            .map(Grant::toString).toList();
        return new TokenView(token.getId(), token.getName(), token.getTokenPrefix(), ceiling, token.getCreatedOn(),
            token.getExpiresOn(), token.getLastUsedOn(), token.getRevokedOn(), status,
            owner == null ? null : new Owner(owner.getId(), owner.getEmail(), owner.getName()));
    }
}
