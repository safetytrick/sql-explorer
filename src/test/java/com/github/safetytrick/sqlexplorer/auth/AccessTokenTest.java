package com.github.safetytrick.sqlexplorer.auth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "sql-explorer.auth.mode=oidc",
    "sql-explorer.auth.issuer=" + AccessTokenTest.ISSUER,
    "sql-explorer.auth.token-max-lifetime=30d",
    "spring.security.oauth2.client.registration.sql-explorer.provider=test-idp",
    "spring.security.oauth2.client.registration.sql-explorer.client-id=sql-explorer",
    "spring.security.oauth2.client.registration.sql-explorer.client-authentication-method=none",
    "spring.security.oauth2.client.registration.sql-explorer.authorization-grant-type=authorization_code",
    "spring.security.oauth2.client.registration.sql-explorer.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
    "spring.security.oauth2.client.registration.sql-explorer.scope=openid,profile,email",
    "spring.security.oauth2.client.provider.test-idp.authorization-uri=" + AccessTokenTest.ISSUER + "/oauth2/authorize",
    "spring.security.oauth2.client.provider.test-idp.token-uri=" + AccessTokenTest.ISSUER + "/oauth2/token",
    "spring.security.oauth2.client.provider.test-idp.jwk-set-uri=" + AccessTokenTest.ISSUER + "/oauth2/jwks",
    "spring.security.oauth2.client.provider.test-idp.user-info-uri=" + AccessTokenTest.ISSUER + "/userinfo",
    "spring.security.oauth2.client.provider.test-idp.user-name-attribute=sub",
})
@Testcontainers
class AccessTokenTest {

    static final String ISSUER = "http://idp.example.invalid";

    private static final String API = "/api/sql-explorer";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.4");

    @DynamicPropertySource
    static void explorerDatabase(DynamicPropertyRegistry registry) {
        registry.add("datasources.explorer.jdbc-url", MYSQL::getJdbcUrl);
        registry.add("datasources.explorer.username", MYSQL::getUsername);
        registry.add("datasources.explorer.password", MYSQL::getPassword);
        registry.add("environments.alpha.default", () -> "true");
        registry.add("environments.beta.default", () -> "false");
        registry.add("parquet-sink.filename", () -> CAPTURES.resolve("sql-repository.parquet").toString());
    }

    private static final Path CAPTURES = tempDirectory();

    private static Path tempDirectory() {
        try {
            Path directory = Files.createTempDirectory("sql-explorer-tokens");
            directory.toFile().deleteOnExit();
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    WebApplicationContext context;

    @Autowired
    JsonMapper json;

    @Autowired
    UserService users;

    @Autowired
    UserRepository userRepository;

    @Autowired
    AccessTokenRepository tokenRepository;

    @Autowired
    ClientRegistrationRepository registrations;

    private MockMvc mvc;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach
    void mockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void aTokenIsMadeListedAndRevoked() throws Exception {
        var me = signedIn("sub-maker", List.of("sqlx:viewer:alpha"));
        JsonNode created = create(me, "laptop", List.of("viewer:alpha"), 7).andExpect(status().isCreated()).body();

        String secret = created.path("secret").asString();
        int id = created.path("token").path("id").asInt();
        assertThat(created.path("token").path("status").asString()).isEqualTo("active");
        assertThat(created.path("token").path("ceiling").valueStream().map(JsonNode::asString).toList()).containsExactly("viewer:alpha");
        assertThat(Instant.parse(created.path("token").path("expiresOn").asString()))
            .isBetween(Instant.now().plus(6, ChronoUnit.DAYS), Instant.now().plus(8, ChronoUnit.DAYS));

        JsonNode list = body(mvc.perform(get(API + "/tokens").contextPath(API).with(me)).andExpect(status().isOk()));
        JsonNode listed = list.path("tokens").get(0);
        assertThat(listed.path("id").asInt()).isEqualTo(id);
        assertThat(listed.path("name").asString()).isEqualTo("laptop");
        assertThat(secret).startsWith(listed.path("prefix").asString());
        assertThat(list.path("maxLifetimeDays").asLong()).isEqualTo(30);
        assertThat(list.path("grants").valueStream().map(JsonNode::asString).toList()).containsExactly("viewer:alpha");
        assertThat(list.path("ceilingOptions").valueStream().map(JsonNode::asString).toList()).containsExactly("viewer:alpha", "reader:alpha");
        assertThat(list.toString()).doesNotContain(secret).doesNotContain(AccessTokenService.hash(secret));

        assertThat(call("GET", "/env/alpha/schemas", secret, null).statusCode()).isEqualTo(200);

        mvc.perform(delete(API + "/tokens/" + id).contextPath(API).with(me).with(asAngularDoes())).andExpect(status().isOk());
        HttpResponse<String> revoked = call("GET", "/env/alpha/schemas", secret, null);
        assertThat(revoked.statusCode()).isEqualTo(401);
        assertThat(json.readTree(revoked.body()).path("message").asString()).contains("revoked");
        assertThat(tokenRepository.findById(id).orElseThrow().getRevokedOn()).isNotNull();
    }

    @Test
    void theSecretIsStoredOnlyAsItsHash() throws Exception {
        var me = signedIn("sub-hash", List.of("sqlx:viewer:alpha"));
        JsonNode created = create(me, "hashed", List.of("viewer:alpha"), null).andExpect(status().isCreated()).body();
        String secret = created.path("secret").asString();
        assertThat(secret).startsWith("sqlx_").hasSize(48);

        AccessToken row = tokenRepository.findById(created.path("token").path("id").asInt()).orElseThrow();
        assertThat(row.getTokenHash()).isEqualTo(AccessTokenService.hash(secret)).doesNotContain(secret);
        assertThat(row.getTokenPrefix()).hasSize(13);
        assertThat(secret).startsWith(row.getTokenPrefix());
        assertThat(row.getExpiresOn()).isBetween(Instant.now().plus(29, ChronoUnit.DAYS), Instant.now().plus(31, ChronoUnit.DAYS));
    }

    @Test
    void aCeilingAboveYourOwnGrantsIsRefused() throws Exception {
        var viewer = signedIn("sub-greedy", List.of("sqlx:viewer:alpha"));
        String refused = create(viewer, "greedy", List.of("runner:alpha"), null)
            .andExpect(status().isBadRequest()).response();
        assertThat(refused).contains("runner:alpha").contains("beyond your own grants");
        create(viewer, "elsewhere", List.of("viewer:beta"), null).andExpect(status().isBadRequest());
        create(viewer, "everywhere", List.of("viewer"), null).andExpect(status().isBadRequest());
        create(viewer, "too long", List.of("viewer:alpha"), 31).andExpect(status().isBadRequest());
        assertThat(tokenRepository.findByUserIdOrderByCreatedOnDescIdDesc(
            userRepository.findByIssuerAndSubject(ISSUER, "sub-greedy").orElseThrow().getId())).isEmpty();
    }

    @Test
    void aUserWhoseGroupsGrantNothingCannotMakeOne() throws Exception {
        String refused = create(signedIn("sub-none", List.of()), "nothing", List.of("viewer:alpha"), null)
            .andExpect(status().isBadRequest()).response();
        assertThat(refused).contains("grant you nothing");
    }

    @Test
    void anAdminsViewerTokenReadsButCannotPull() throws Exception {
        var admin = signedIn("sub-admin", List.of("sqlx:admin"));
        String viewerToken = secret(create(admin, "read only", List.of("viewer:alpha"), null));
        String adminToken = secret(create(admin, "full", List.of("admin"), null));

        assertThat(call("GET", "/env/alpha/schemas", viewerToken, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/env/beta/schemas", viewerToken, null).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/environments/alpha/pull", viewerToken, "").statusCode()).isEqualTo(403);
        assertThat(call("GET", "/analysis-failures", viewerToken, null).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/analysis-failures", adminToken, null).statusCode()).isEqualTo(200);

        JsonNode me = json.readTree(call("GET", "/user", viewerToken, null).body());
        assertThat(me.path("grants").valueStream().map(JsonNode::asString).toList()).containsExactly("viewer:alpha");
        assertThat(me.path("admin").asBoolean()).isFalse();
        assertThat(me.path("token").path("name").asString()).isEqualTo("read only");
        assertThat(me.path("environments").propertyNames()).containsExactly("alpha");
    }

    @Test
    void theOwnerLosingAGroupNarrowsTheirTokens() throws Exception {
        var admin = signedIn("sub-shrinks", List.of("sqlx:admin"));
        String token = secret(create(admin, "wide", List.of("admin"), null));
        assertThat(call("GET", "/analysis-failures", token, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/env/beta/schemas", token, null).statusCode()).isEqualTo(200);

        users.signedIn(ISSUER, "sub-shrinks", "sub-shrinks@example.com", "Shrinks", List.of("sqlx:viewer:alpha"),
            Instant.now().plusSeconds(5));

        assertThat(call("GET", "/analysis-failures", token, null).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/env/beta/schemas", token, null).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/env/alpha/schemas", token, null).statusCode()).isEqualTo(200);

        users.signedIn(ISSUER, "sub-shrinks", "sub-shrinks@example.com", "Shrinks", List.of("sqlx:admin"),
            Instant.now().minus(1, ChronoUnit.HOURS));
        assertThat(call("GET", "/analysis-failures", token, null).statusCode()).isEqualTo(403);
    }

    @Test
    void anExpiredOrUnknownTokenIs401Json() throws Exception {
        String secret = secret(create(signedIn("sub-expires", List.of("sqlx:viewer:alpha")), "short", List.of("viewer:alpha"), 1));
        AccessToken row = tokenRepository.findByTokenHash(AccessTokenService.hash(secret)).orElseThrow();
        row.setExpiresOn(Instant.now().minusSeconds(1));
        tokenRepository.save(row);

        HttpResponse<String> expired = call("GET", "/env/alpha/schemas", secret, null);
        assertThat(expired.statusCode()).isEqualTo(401);
        assertThat(expired.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(expired.headers().firstValue("WWW-Authenticate").orElse("")).startsWith("Bearer");
        assertThat(json.readTree(expired.body()).path("message").asString()).contains("expired");

        String forged = "sqlx_" + "A".repeat(43);
        assertThat(call("GET", "/env/alpha/schemas", forged, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/env/alpha/schemas", "not-even-ours", null).statusCode()).isEqualTo(401);
    }

    @Test
    void aTokenCannotCallTokens() throws Exception {
        String token = secret(create(signedIn("sub-minter", List.of("sqlx:admin")), "minter", List.of("admin"), null));
        HttpResponse<String> list = call("GET", "/tokens", token, null);
        assertThat(list.statusCode()).isEqualTo(403);
        assertThat(json.readTree(list.body()).path("message").asString()).contains("cannot manage access tokens");
        assertThat(call("POST", "/tokens", token, "{\"name\":\"child\",\"ceiling\":[\"admin\"]}").statusCode()).isEqualTo(403);
        assertThat(call("DELETE", "/tokens/1", token, null).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/%74okens", token, null).statusCode()).isIn(400, 403, 404);
        assertThat(call("GET", "/tokens", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void writesAreAuthoredByTheOwner() throws Exception {
        String token = secret(create(signedIn("sub-author", List.of("sqlx:viewer:alpha")), "writer", List.of("viewer:alpha"), null));
        HttpResponse<String> note = send(HttpRequest.newBuilder(uri("/env/alpha/notes?path=/schema/app_main&text=from%20a%20token"))
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.noBody()));
        assertThat(note.statusCode()).as(note.body()).isBetween(200, 299);
        User owner = userRepository.findByIssuerAndSubject(ISSUER, "sub-author").orElseThrow();
        assertThat(json.readTree(note.body()).path("author").path("id").asInt()).isEqualTo(owner.getId());
        assertThat(note.headers().allValues("Set-Cookie")).as("stateless: no session")
            .noneMatch(cookie -> cookie.startsWith("JSESSIONID"));
    }

    @Test
    void lastUsedIsRecordedButNotOnEveryRequest() throws Exception {
        String secret = secret(create(signedIn("sub-used", List.of("sqlx:viewer:alpha")), "used", List.of("viewer:alpha"), null));
        call("GET", "/user", secret, null);
        Instant first = tokenRepository.findByTokenHash(AccessTokenService.hash(secret)).orElseThrow().getLastUsedOn();
        assertThat(first).isNotNull();
        call("GET", "/user", secret, null);
        assertThat(tokenRepository.findByTokenHash(AccessTokenService.hash(secret)).orElseThrow().getLastUsedOn()).isEqualTo(first);
    }

    @Test
    void anAdminMayListAndRevokeAnyonesOthersMayNot() throws Exception {
        var owner = signedIn("sub-owner", List.of("sqlx:viewer:alpha"));
        int id = create(owner, "theirs", List.of("viewer:alpha"), null).body().path("token").path("id").asInt();

        var other = signedIn("sub-other", List.of("sqlx:viewer:alpha"));
        mvc.perform(delete(API + "/tokens/" + id).contextPath(API).with(other).with(asAngularDoes())).andExpect(status().isNotFound());
        mvc.perform(get(API + "/tokens?all=true").contextPath(API).with(other)).andExpect(status().isForbidden());

        var admin = signedIn("sub-admin2", List.of("sqlx:admin"));
        JsonNode all = body(mvc.perform(get(API + "/tokens?all=true").contextPath(API).with(admin)).andExpect(status().isOk()));
        assertThat(all.path("tokens").valueStream().anyMatch(t -> t.path("id").asInt() == id
            && "sub-owner@example.com".equals(t.path("owner").path("email").asString()))).isTrue();
        mvc.perform(delete(API + "/tokens/" + id).contextPath(API).with(admin).with(asAngularDoes())).andExpect(status().isOk());
    }

    @Test
    void makingATokenNeedsTheCsrfToken() throws Exception {
        mvc.perform(post(API + "/tokens").contextPath(API).with(signedIn("sub-csrf", List.of("sqlx:viewer:alpha")))
                .contentType("application/json").content("{\"name\":\"x\",\"ceiling\":[\"viewer:alpha\"]}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void mcpNeedsAToken() throws Exception {
        HttpResponse<String> anonymous = mcp(null, "tools/list", Map.of());
        assertThat(anonymous.statusCode()).isEqualTo(401);

        String token = secret(create(signedIn("sub-mcp", List.of("sqlx:viewer:alpha")), "mcp", List.of("viewer:alpha"), null));
        HttpResponse<String> listed = mcp(token, "tools/list", Map.of());
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        assertThat(listed.body()).contains("search_queries");
    }

    @Test
    void mcpTakesAPageOnItsOwnAddressThroughAProxy() throws Exception {
        String token = secret(create(signedIn("sub-mcp-origin", List.of("sqlx:viewer:alpha")), "page", List.of("viewer:alpha"), null));
        Map<String, String> tunnel = Map.of(
            "X-Forwarded-Proto", "https", "X-Forwarded-Host", "tunnel.example.test", "X-Forwarded-Port", "443");

        assertThat(mcpFrom(token, "https://tunnel.example.test", tunnel).statusCode()).isEqualTo(200);
        assertThat(mcpFrom(token, "http://tunnel.example.test", tunnel).statusCode()).as("scheme differs").isEqualTo(403);
        assertThat(mcpFrom(token, "https://attacker.example.com", tunnel).statusCode()).isEqualTo(403);
        Map<String, String> spoofed = Map.of("X-Sql-Explorer-Own-Origin", "https://attacker.example.com");
        assertThat(mcpFrom(token, "https://attacker.example.com", spoofed).statusCode())
            .as("a client's own claim to the address is replaced").isEqualTo(403);
        assertThat(mcpFrom(token, "http://localhost:4200", Map.of()).statusCode()).isEqualTo(200);
    }

    @Test
    void mcpToolsRespectTheTokensGrants() throws Exception {
        String token = secret(create(signedIn("sub-mcp2", List.of("sqlx:admin")), "alpha reader", List.of("viewer:alpha"), null));

        JsonNode read = mcpResult(token, "list_tags", Map.of("environment", "alpha"));
        assertThat(read.path("isError").asBoolean()).as(read.toString()).isFalse();

        JsonNode elsewhere = mcpResult(token, "list_tags", Map.of("environment", "beta"));
        assertThat(elsewhere.path("isError").asBoolean()).isTrue();
        assertThat(text(elsewhere)).contains("explorer:read").contains("beta");

        JsonNode write = mcpResult(token, "add_tag", Map.of("environment", "beta", "tag", "x", "schema", "app_main"));
        assertThat(write.path("isError").asBoolean()).isTrue();
        assertThat(text(write)).contains("commentary:write").contains("beta");

        JsonNode defaulted = mcpResult(token, "list_tags", Map.of());
        assertThat(defaulted.path("isError").asBoolean()).as(defaulted.toString()).isFalse();

        JsonNode pure = mcpResult(token, "normalize_sql", Map.of("sql", "select 1"));
        assertThat(pure.path("isError").asBoolean()).as(pure.toString()).isFalse();
    }

    @Test
    void anMcpWriteIsAuthoredByTheOwner() throws Exception {
        String token = secret(create(signedIn("sub-tagger", List.of("sqlx:viewer:alpha")), "tagger", List.of("viewer:alpha"), null));
        JsonNode added = mcpResult(token, "add_tag", Map.of("environment", "alpha", "tag", "from-mcp", "schema", "app_main"));
        assertThat(added.path("isError").asBoolean()).as(added.toString()).isFalse();
        User owner = userRepository.findByIssuerAndSubject(ISSUER, "sub-tagger").orElseThrow();
        assertThat(json.readTree(text(added)).path("author").path("id").asInt()).isEqualTo(owner.getId());
    }

    private record Made(ResultActions actions, AccessTokenTest test) {
        Made andExpect(org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception {
            actions.andExpect(matcher);
            return this;
        }

        JsonNode body() throws Exception {
            return test.body(actions);
        }

        String response() throws Exception {
            return actions.andReturn().getResponse().getContentAsString();
        }
    }

    private Made create(OidcLoginRequestPostProcessor who, String name, List<String> ceiling, Integer days) throws Exception {
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("name", name);
        request.put("ceiling", ceiling);
        if (days != null) {
            request.put("expiresInDays", days);
        }
        return new Made(mvc.perform(post(API + "/tokens").contextPath(API).with(who).with(asAngularDoes())
            .contentType("application/json").content(json.writeValueAsString(request))), this);
    }

    private String secret(Made made) throws Exception {
        return made.andExpect(status().isCreated()).body().path("secret").asString();
    }

    JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return send(request);
    }

    private HttpResponse<String> mcp(String token, String method, Map<String, Object> params) throws Exception {
        String body = json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return send(request);
    }

    private HttpResponse<String> mcpFrom(String token, String origin, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("Authorization", "Bearer " + token)
            .header("Origin", origin)
            .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"));
        headers.forEach(request::header);
        return send(request);
    }

    private JsonNode mcpResult(String token, String tool, Map<String, Object> arguments) throws Exception {
        HttpResponse<String> response = mcp(token, "tools/call", Map.of("name", tool, "arguments", arguments));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("result");
    }

    private static String text(JsonNode result) {
        return result.path("content").get(0).path("text").asString();
    }

    private OidcLoginRequestPostProcessor signedIn(String subject, List<String> groups) {
        return oidcLogin()
            .clientRegistration(registrations.findByRegistrationId(SecurityConfig.REGISTRATION))
            .idToken(token -> token
                .issuer(ISSUER)
                .subject(subject)
                .issuedAt(Instant.now())
                .claim("email", subject + "@example.com")
                .claim("name", "User " + subject)
                .claim("groups", groups));
    }

    private static RequestPostProcessor asAngularDoes() {
        return request -> {
            request.setCookies(new Cookie("XSRF-TOKEN", "token-from-the-cookie"));
            request.addHeader("X-XSRF-TOKEN", "token-from-the-cookie");
            return request;
        };
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + API + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
