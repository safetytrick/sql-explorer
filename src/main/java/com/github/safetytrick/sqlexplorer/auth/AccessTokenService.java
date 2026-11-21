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

@Service
public class AccessTokenService {

    public static final String SECRET_PREFIX = "sqlx_";

    static final int DISPLAY_PREFIX = SECRET_PREFIX.length() + 8;

    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private static final int SECRET_BYTES = 32;
    private static final int MAX_NAME = 64;
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

    void setClock(Clock clock) {
        this.clock = clock;
    }

    public record Created(AccessToken token, String secret) {}

    public Grants ownerGrants(User owner) {
        List<String> groups = owner.getGroupsSnapshot();
        return groups == null ? Grants.NOTHING : groupGrants.of(groups);
    }

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

    public long maxLifetimeDays() {
        return Math.max(1, auth.tokenMaxLifetime().toDays());
    }

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

    public AccessToken revoke(AccessToken token, User by) {
        if (token.getRevokedOn() == null) {
            token.setRevokedOn(now());
            token = tokens.save(token);
            log.info("access token {} \"{}\" of user {} revoked by user {}",
                token.getId(), token.getName(), token.getUserId(), by.getId());
        }
        return token;
    }

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

    public record Presented(AccessTokenAuthentication authentication, Refusal refusal) {}

    public Presented authenticate(String secret) {
        if (secret == null || !secret.startsWith(SECRET_PREFIX) || secret.length() != SECRET_LENGTH) {
            return new Presented(null, Refusal.UNKNOWN);
        }
        String hash = hash(secret);
        Optional<AccessToken> found = tokens.findByTokenHash(hash);
        if (found.isEmpty() || !MessageDigest.isEqual(
                found.get().getTokenHash().getBytes(StandardCharsets.US_ASCII), hash.getBytes(StandardCharsets.US_ASCII))) {
            return new Presented(null, Refusal.UNKNOWN);
        }
        AccessToken token = found.get();
        Instant now = now();
        if (token.getRevokedOn() != null) {
            return new Presented(null, Refusal.REVOKED);
        }
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
