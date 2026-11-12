package com.github.safetytrick.sqlexplorer.auth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
import org.springframework.test.web.servlet.MvcResult;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sign-in on ({@code sql-explorer.auth.mode: oidc}), booted against a real
 * MySQL like ApplicationContextTest, with a client whose provider lives at a
 * name that never resolves. Nothing here reaches it: the registration is built
 * without discovery, and the tests stop at the redirect to its authorize
 * endpoint or stand in for a completed sign-in with {@code oidcLogin()}.
 *
 * <p>Sign-in off is ApplicationContextTest, which boots the default.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "sql-explorer.auth.mode=oidc",
    "sql-explorer.auth.issuer=" + OidcSignInTest.ISSUER,
    "sql-explorer.auth.end-session-uri=" + OidcSignInTest.ISSUER + "/connect/logout",
    "spring.security.oauth2.client.registration.sql-explorer.provider=test-idp",
    "spring.security.oauth2.client.registration.sql-explorer.client-id=sql-explorer",
    "spring.security.oauth2.client.registration.sql-explorer.client-authentication-method=none",
    "spring.security.oauth2.client.registration.sql-explorer.authorization-grant-type=authorization_code",
    "spring.security.oauth2.client.registration.sql-explorer.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
    "spring.security.oauth2.client.registration.sql-explorer.scope=openid,profile,email",
    "spring.security.oauth2.client.provider.test-idp.authorization-uri=" + OidcSignInTest.ISSUER + "/oauth2/authorize",
    "spring.security.oauth2.client.provider.test-idp.token-uri=" + OidcSignInTest.ISSUER + "/oauth2/token",
    "spring.security.oauth2.client.provider.test-idp.jwk-set-uri=" + OidcSignInTest.ISSUER + "/oauth2/jwks",
    "spring.security.oauth2.client.provider.test-idp.user-info-uri=" + OidcSignInTest.ISSUER + "/userinfo",
    "spring.security.oauth2.client.provider.test-idp.user-name-attribute=sub",
})
@Testcontainers
class OidcSignInTest {

    /** Under .invalid, which never resolves (RFC 6761): nothing can be reached by accident. */
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
        registry.add("parquet-sink.filename", () -> CAPTURES.resolve("sql-repository.parquet").toString());
    }

    private static final Path CAPTURES = tempDirectory();

    private static Path tempDirectory() {
        try {
            Path directory = Files.createTempDirectory("sql-explorer-oidc");
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
    UserRepository repository;

    @Autowired
    ClientRegistrationRepository registrations;

    private MockMvc mvc;

    @BeforeEach
    void mockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ---- over real HTTP: what a browser and an XHR actually get ----

    /** An XHR cannot follow a redirect to a login page; it gets a 401 it can act on. */
    @Test
    void anApiCallWithoutASessionIs401JsonNotARedirect() throws Exception {
        for (String path : List.of("/user", "/environments", "/env/alpha/tags")) {
            HttpResponse<String> response = send(HttpRequest.newBuilder(uri(path)).GET(), noRedirects());

            assertThat(response.statusCode()).as(path).isEqualTo(401);
            assertThat(response.headers().firstValue("Location")).as(path).isEmpty();
            assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
            JsonNode body = json.readTree(response.body());
            assertThat(body.path("login").asString()).isEqualTo(API + "/login");
        }
    }

    /** A browser sent to /login goes to the provider, by way of Spring's authorization endpoint, with PKCE. */
    @Test
    void theLoginEntryPointRedirectsToTheProviderWithPkce() throws Exception {
        HttpClient browser = HttpClient.newBuilder().cookieHandler(new CookieManager())
            .followRedirects(HttpClient.Redirect.NEVER).build();

        HttpResponse<String> login = send(HttpRequest.newBuilder(uri("/login?return=/queries")).GET(), browser);
        assertThat(login.statusCode()).isEqualTo(302);
        String spring = login.headers().firstValue("Location").orElseThrow();
        assertThat(spring).endsWith(API + "/oauth2/authorization/sql-explorer");

        HttpResponse<String> authorize = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port).resolve(spring)).GET(), browser);
        assertThat(authorize.statusCode()).isEqualTo(302);
        String provider = authorize.headers().firstValue("Location").orElseThrow();
        assertThat(provider).startsWith(ISSUER + "/oauth2/authorize?")
            .contains("client_id=sql-explorer")
            .contains("code_challenge_method=S256")
            .contains("code_challenge=")
            // the callback is under the servlet context path; dev-idp registers exactly this shape
            .contains("redirect_uri=http://localhost:" + port + API + "/login/oauth2/code/sql-explorer");
    }

    /** MCP clients cannot sign in. Until personal access tokens (sqlx-e13.5) the endpoint stays open, without CSRF. */
    @Test
    void mcpStaysUsableWithoutASession() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}")),
            noRedirects());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("search_queries");
    }

    @Test
    void healthStaysOpen() throws Exception {
        assertThat(send(HttpRequest.newBuilder(uri("/actuator/health")).GET(), noRedirects()).statusCode()).isEqualTo(200);
    }

    // ---- signed in: a stand-in for a completed sign-in ----

    @Test
    void userReportsTheSignedInUserAndTheirGroups() throws Exception {
        MvcResult result = mvc.perform(get(API + "/user").contextPath(API)
                .with(signedIn("sub-reader", "reader@example.com", List.of("sqlx:viewer", "sqlx:runner:app-staging"))))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("mode").asString()).isEqualTo("oidc");
        assertThat(body.path("issuer").asString()).isEqualTo(ISSUER);
        assertThat(body.path("subject").asString()).isEqualTo("sub-reader");
        assertThat(body.path("groups").valueStream().map(JsonNode::asString).toList())
            .containsExactly("sqlx:viewer", "sqlx:runner:app-staging");
        assertThat(body.path("user").path("email").asString()).isEqualTo("reader@example.com");
        assertThat(body.path("user").path("name").asString()).isEqualTo("Reader sub-reader");
        // the author shape stays free of provider identifiers
        assertThat(body.path("user").has("issuer")).isFalse();
        assertThat(body.path("user").has("subject")).isFalse();
    }

    /** The signed-in user, not the legacy admin, is who writes are recorded against. */
    @Test
    void writesAreAttributedToTheSignedInUser() throws Exception {
        MvcResult result = mvc.perform(post(API + "/env/alpha/notes").contextPath(API)
                .with(signedIn("sub-writer", "writer@example.com", List.of()))
                .with(asAngularDoes())
                .param("path", "/schema/app_main/table/account")
                .param("text", "a note"))
            .andExpect(status().is2xxSuccessful())
            .andReturn();

        User writer = repository.findByIssuerAndSubject(ISSUER, "sub-writer").orElseThrow();
        JsonNode note = json.readTree(result.getResponse().getContentAsString());
        assertThat(note.path("author").path("id").asInt()).isEqualTo(writer.getId());
        assertThat(note.path("author").path("email").asString()).isEqualTo("writer@example.com");
    }

    @Test
    void aWriteWithoutTheCsrfTokenIsRefused() throws Exception {
        mvc.perform(post(API + "/env/alpha/notes").contextPath(API)
                .with(signedIn("sub-forged", "forged@example.com", List.of()))
                .param("path", "/schema/app_main")
                .param("text", "x"))
            .andExpect(status().isForbidden());
    }

    /** Sign-out answers with where to go: the provider's end-session endpoint, which sends the browser back. */
    @Test
    void signOutIsRpInitiated() throws Exception {
        MvcResult result = mvc.perform(post(API + "/logout").contextPath(API)
                .with(signedIn("sub-leaver", "leaver@example.com", List.of()))
                .with(asAngularDoes()))
            .andExpect(status().isOk())
            .andReturn();

        String location = json.readTree(result.getResponse().getContentAsString()).path("location").asString();
        assertThat(location).startsWith(ISSUER + "/connect/logout?")
            .contains("id_token_hint=")
            .contains("post_logout_redirect_uri=http://localhost/");
    }

    // ---- the users row ----

    @Test
    void signInCreatesThenUpdatesOneRowPerIssuerAndSubject() {
        User first = users.signedIn(ISSUER, "sub-upsert", "old@example.com", "Old Name");
        User again = users.signedIn(ISSUER, "sub-upsert", "new@example.com", "New Name");

        assertThat(again.getId()).isEqualTo(first.getId());
        User stored = repository.findById(first.getId()).orElseThrow();
        assertThat(stored.getEmail()).isEqualTo("new@example.com");
        assertThat(stored.getName()).isEqualTo("New Name");

        // the same subject at another provider is another person
        User elsewhere = users.signedIn("http://other.example.invalid", "sub-upsert", "new@example.com", "New Name");
        assertThat(elsewhere.getId()).isNotEqualTo(first.getId());
    }

    /** Work no person started (startup, schedules, MCP) stays with the legacy admin. */
    @Test
    void withoutASignedInUserTheAuthorIsTheLegacyAdmin() {
        assertThat(users.currentUser().getEmail()).isEqualTo(UserService.LEGACY_ADMIN);
        assertThat(users.currentUser().getIssuer()).isNull();
    }

    private OidcLoginRequestPostProcessor signedIn(String subject, String email, List<String> groups) {
        return oidcLogin()
            .clientRegistration(registrations.findByRegistrationId(SecurityConfig.REGISTRATION))
            .idToken(token -> token
                .issuer(ISSUER)
                .subject(subject)
                .claim("email", email)
                .claim("name", "Reader " + subject)
                .claim("groups", groups));
    }

    /**
     * The CSRF token the way Angular's HttpClient sends it: the XSRF-TOKEN
     * cookie's value, echoed in X-XSRF-TOKEN. Spring Security Test's csrf()
     * sends a masked token instead, which is the form-post shape, not this.
     */
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

    private static HttpClient noRedirects() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static HttpResponse<String> send(HttpRequest.Builder request, HttpClient client) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
