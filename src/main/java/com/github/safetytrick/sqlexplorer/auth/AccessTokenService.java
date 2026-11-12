package com.github.safetytrick.sqlexplorer.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Personal access tokens (sqlx-e13.5): made, listed, revoked, and turned
 * back into an {@link AccessTokenAuthentication} when one is presented.
 *
 * <h2>What a token may do</h2>
 * The intersection of two sets of grants, re-evaluated on every request:
 * <ul>
 *   <li><b>its owner's</b>, from the groups seen at the owner's latest sign-in
 *       ({@code users.groups_snapshot}); and</li>
 *   <li><b>its ceiling</b>, chosen when it was made and never above the
 *       owner's grants at that moment: an administrator can make a token that
 *       only reads one environment.</li>
 * </ul>
 *
 * <p><b>The limitation.</b> Groups only ever arrive in an ID token, at
 * sign-in; a token has no ID token. So a group the provider takes away
 * narrows the owner's tokens when the owner next signs in, not at the moment
 * it is removed. A person who never signs in again keeps their tokens' access
 * until the tokens expire, which is why every token expires and why the
 * maximum lifetime ({@code sql-explorer.auth.token-max-lifetime}, 90 days by
 * default) is configurable. Revoking the token, or removing the person at the
 * provider and then signing in as them, ends it sooner. Closing this gap needs
 * the provider to be asked directly (SCIM, or a refresh token kept per user),
 * which is not done.
 *
 * <h2>The secret</h2>
 * {@code sqlx_} and 32 random bytes, base64url: recognisable in a leaked
 * config and to secret scanners, and with 256 bits of entropy a plain SHA-256
 * is enough to store it (no salt or slow hash is needed when the input cannot
 * be guessed). Only the hash is kept; the secret is shown once.
 */
@Service
public class AccessTokenService {

    /** What every secret starts with. */
    public static final String SECRET_PREFIX = "sqlx_";

    /** How many characters of the secret are kept to show in lists: the prefix and 8 more. */
    static final int DISPLAY_PREFIX = SECRET_PREFIX.length() + 8;

    /** A use is recorded at most this often per token. */
    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private static final int SECRET_BYTES = 32;
    private static final int MAX_NAME = 64;
    /** 32 bytes of base64url without padding. */
    private static final int SECRET_LENGTH = SECRET_PREFIX.length() + 43;

    private static final Logger log = LoggerFactory.getLogger(AccessTokenService.class);

    private final AccessTokenRepository tokens;
    private final UserRepository users;
    private final GroupGrants groupGrants;
    private final AuthProperties auth;
    private final EnvironmentRegistry registry;
    private final SecureRandom random = new SecureRandom();
    private Clock clock = Clock.systemUTC();

    public AccessTokenService(AccessTokenRepository tokens, UserRepository users, GroupGrants groupGrants,
                              AuthProperties auth, EnvironmentRegistry registry) {
        this.tokens = tokens;
        this.users = users;
        this.groupGrants = groupGrants;
        this.auth = auth;
        this.registry = registry;
    }

    /** For tests that need time to pass. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    // ── made, listed, revoked ──────────────────────────────────────────────

    /** A token just made: the row, and the secret, which is never available again. */
    public record Created(AccessToken token, String secret) {}

    /**
     * What a user's own grants are, as tokens see them: those of the groups at
     * their latest sign-in. Nothing for a user with no snapshot.
     */
    public Grants ownerGrants(User owner) {
        List<String> groups = owner.getGroupsSnapshot();
        return groups == null ? Grants.NOTHING : groupGrants.of(groups);
    }

    /**
     * Makes a token for {@code owner}.
     *
     * @param ceiling      grants in the groups syntax; each must be within the
     *                     owner's own
     * @param lifetimeDays how long it lives; at most the configured maximum,
     *                     which is also the default
     * @throws IllegalArgumentException with a message for the person making it
     */
    public Created create(User owner, String name, List<String> ceiling, Integer lifetimeDays) {
        String label = name == null ? "" : name.strip();
        if (label.isEmpty()) {
            throw new IllegalArgumentException("Give the token a name, so you can tell it apart later.");
        }
        if (label.length() > MAX_NAME) {
            throw new IllegalArgumentException("A token's name is at most " + MAX_NAME + " characters.");
        }
        Grants own = ownerGrants(owner);
        if (owner.getGroupsSnapshot() == null || own.grants().isEmpty()) {
            throw new IllegalArgumentException("Your groups grant you nothing here, so a token could do nothing. "
                + "If that is wrong, sign out and in again so your current groups are seen.");
        }
        Grants wanted = parseCeiling(ceiling);
        if (wanted.grants().isEmpty()) {
            throw new IllegalArgumentException("Choose at least one grant for the token's ceiling.");
        }
        List<String> beyond = wanted.grants().stream().filter(g -> !own.includes(g)).map(Grant::toString).toList();
        if (!beyond.isEmpty()) {
            throw new IllegalArgumentException("A token cannot have more access than you: " + String.join(", ", beyond)
                + " is beyond your own grants (" + describe(own) + ").");
        }
        Instant now = now();
        Instant expires = now.plus(lifetime(lifetimeDays));

        String secret = newSecret();
        AccessToken token = new AccessToken();
        token.setUserId(owner.getId());
        token.setName(label);
        token.setTokenHash(hash(secret));
        token.setTokenPrefix(secret.substring(0, DISPLAY_PREFIX));
        token.setCeiling(formatCeiling(wanted));
        token.setCreatedOn(now);
        token.setExpiresOn(expires);
        AccessToken saved = tokens.save(token);
        log.info("access token {} \"{}\" made by user {}: ceiling {}, expires {}",
            saved.getId(), label, owner.getId(), saved.getCeiling(), expires);
        return new Created(saved, secret);
    }

    private Duration lifetime(Integer days) {
        Duration max = auth.tokenMaxLifetime();
        if (days == null) {
            return max;
        }
        if (days < 1) {
            throw new IllegalArgumentException("A token lives at least a day.");
        }
        Duration wanted = Duration.ofDays(days);
        if (wanted.compareTo(max) > 0) {
            throw new IllegalArgumentException("A token lives at most " + maxLifetimeDays() + " days here.");
        }
        return wanted;
    }

    /** The configured maximum, in whole days, as the UI offers it. */
    public long maxLifetimeDays() {
        return Math.max(1, auth.tokenMaxLifetime().toDays());
    }

    /** {@code revoked}, {@code expired} (by its own expiry or the maximum as configured now) or {@code active}. */
    public String status(AccessToken token) {
        Instant now = now();
        if (token.getRevokedOn() != null) {
            return "revoked";
        }
        boolean expired = !now.isBefore(token.getExpiresOn())
            || !now.isBefore(token.getCreatedOn().plus(auth.tokenMaxLifetime()));
        return expired ? "expired" : "active";
    }

    public List<AccessToken> listOwn(User owner) {
        return tokens.findByUserIdOrderByCreatedOnDescIdDesc(owner.getId());
    }

    public List<AccessToken> listAll() {
        return tokens.findAllByOrderByCreatedOnDescIdDesc();
    }

    public Optional<AccessToken> find(Integer id) {
        return tokens.findById(id);
    }

    /** Revokes it now; revoking it again changes nothing. */
    public AccessToken revoke(AccessToken token, User by) {
        if (token.getRevokedOn() == null) {
            token.setRevokedOn(now());
            token = tokens.save(token);
            log.info("access token {} \"{}\" of user {} revoked by user {}",
                token.getId(), token.getName(), token.getUserId(), by.getId());
        }
        return token;
    }

    /**
     * What can be offered as a ceiling to someone holding {@code own}: each of
     * their grants and every lower role on the same scope, and for a grant
     * that covers every environment, the same narrowed to each one.
     */
    public List<String> ceilingOptions(Grants own) {
        Set<String> options = new LinkedHashSet<>();
        List<String> environments = registry.list().stream().map(Environment::getName).sorted().toList();
        for (Grant grant : own.grants()) {
            List<Scope> scopes = new ArrayList<>();
            scopes.add(grant.scope());
            if (grant.scope().environment() == null) {
                environments.forEach(env -> scopes.add(Scope.environment(env)));
            }
            for (Scope scope : scopes) {
                for (int r = grant.role().ordinal(); r >= 0; r--) {
                    options.add(new Grant(Role.values()[r], scope).toString());
                }
            }
        }
        return List.copyOf(options);
    }

    // ── the ceiling's text ─────────────────────────────────────────────────

    /**
     * Grants in the groups syntax, as {@link GroupGrants#parse} reads them, and
     * also {@code role:*} for everywhere, which is how grants are written back
     * ({@link Grant#toString()}).
     *
     * @throws IllegalArgumentException naming what does not parse
     */
    static Grants parseCeiling(List<String> texts) {
        Set<Grant> grants = new LinkedHashSet<>();
        List<String> bad = new ArrayList<>();
        for (String raw : texts == null ? List.<String>of() : texts) {
            String text = raw == null ? "" : raw.strip();
            if (text.isEmpty()) {
                continue;
            }
            Grant grant = text.endsWith(":*")
                ? GroupGrants.parse(text.substring(0, text.length() - 2))
                : GroupGrants.parse(text);
            if (grant == null) {
                bad.add(text);
            } else {
                grants.add(grant);
            }
        }
        if (!bad.isEmpty()) {
            throw new IllegalArgumentException("Not a grant: " + String.join(", ", bad)
                + ". Write <role>[:<environment>[/<datasource>]], role one of viewer, runner, author, admin.");
        }
        return new Grants(new ArrayList<>(grants));
    }

    static Grants parseCeiling(String stored) {
        return parseCeiling(stored == null ? List.of() : List.of(stored.split(",")));
    }

    /**
     * A token's ceiling as stored. One that no longer parses (a role renamed
     * since, say) grants nothing rather than failing every request the token
     * makes: closed, and the token can be revoked and made again.
     */
    static Grants storedCeiling(AccessToken token) {
        try {
            return parseCeiling(token.getCeiling());
        } catch (IllegalArgumentException e) {
            log.warn("access token {} has a ceiling that no longer parses, so it grants nothing: {}",
                token.getId(), e.getMessage());
            return Grants.NOTHING;
        }
    }

    static String formatCeiling(Grants grants) {
        return grants.grants().stream().map(Grant::toString).collect(Collectors.joining(","));
    }

    private static String describe(Grants grants) {
        return grants.grants().isEmpty() ? "none"
            : grants.grants().stream().map(Grant::toString).collect(Collectors.joining(", "));
    }

    // ── presented ─────────────────────────────────────────────────────────

    /** Why a presented token was refused; the message is the 401's. */
    public enum Refusal {
        UNKNOWN("This access token is not recognised."),
        EXPIRED("This access token has expired. Make a new one under Access tokens."),
        REVOKED("This access token has been revoked.");

        private final String message;

        Refusal(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    /** Either an authentication or the reason there is none. */
    public record Presented(AccessTokenAuthentication authentication, Refusal refusal) {}

    /**
     * The authentication for a presented secret, or why there is none.
     * Records the use, at most once a minute per token.
     */
    public Presented authenticate(String secret) {
        if (secret == null || !secret.startsWith(SECRET_PREFIX) || secret.length() != SECRET_LENGTH) {
            return new Presented(null, Refusal.UNKNOWN);
        }
        String hash = hash(secret);
        Optional<AccessToken> found = tokens.findByTokenHash(hash);
        // the lookup is by hash already; compare again in constant time, so nothing hinges on how the index compares
        if (found.isEmpty() || !MessageDigest.isEqual(
                found.get().getTokenHash().getBytes(StandardCharsets.US_ASCII), hash.getBytes(StandardCharsets.US_ASCII))) {
            return new Presented(null, Refusal.UNKNOWN);
        }
        AccessToken token = found.get();
        Instant now = now();
        if (token.getRevokedOn() != null) {
            return new Presented(null, Refusal.REVOKED);
        }
        // the maximum as configured now, too: lowering it takes effect on tokens already made
        Instant cap = token.getCreatedOn().plus(auth.tokenMaxLifetime());
        if (!now.isBefore(token.getExpiresOn()) || !now.isBefore(cap)) {
            return new Presented(null, Refusal.EXPIRED);
        }
        User owner = users.findById(token.getUserId()).orElse(null);
        if (owner == null) {
            return new Presented(null, Refusal.UNKNOWN);
        }
        Grants effective = ownerGrants(owner).intersect(storedCeiling(token));
        tokens.touch(token.getId(), now, now.minus(TOUCH_INTERVAL));
        AccessTokenAuthentication.Holder holder =
            new AccessTokenAuthentication.Holder(owner.getId(), token.getId(), token.getName());
        return new Presented(new AccessTokenAuthentication(holder, effective), null);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
