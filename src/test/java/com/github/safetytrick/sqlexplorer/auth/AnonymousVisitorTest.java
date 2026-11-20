package com.github.safetytrick.sqlexplorer.auth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
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
 * Sign-in on, with visitors allowed to read one environment
 * ({@code sql-explorer.auth.anonymous: viewer:beta}, sqlx-2lc). Sign-in on
 * without visitors is OidcSignInTest, where everything without a session is 401.
 */
@SpringBootTest(properties = {
    "sql-explorer.auth.mode=oidc",
    "sql-explorer.auth.anonymous=viewer:beta",
    "sql-explorer.auth.issuer=" + AnonymousVisitorTest.ISSUER,
    "spring.security.oauth2.client.registration.sql-explorer.provider=test-idp",
    "spring.security.oauth2.client.registration.sql-explorer.client-id=sql-explorer",
    "spring.security.oauth2.client.registration.sql-explorer.client-authentication-method=none",
    "spring.security.oauth2.client.registration.sql-explorer.authorization-grant-type=authorization_code",
    "spring.security.oauth2.client.registration.sql-explorer.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
    "spring.security.oauth2.client.registration.sql-explorer.scope=openid,profile,email",
    "spring.security.oauth2.client.provider.test-idp.authorization-uri=" + AnonymousVisitorTest.ISSUER + "/oauth2/authorize",
    "spring.security.oauth2.client.provider.test-idp.token-uri=" + AnonymousVisitorTest.ISSUER + "/oauth2/token",
    "spring.security.oauth2.client.provider.test-idp.jwk-set-uri=" + AnonymousVisitorTest.ISSUER + "/oauth2/jwks",
    "spring.security.oauth2.client.provider.test-idp.user-name-attribute=sub",
})
@Testcontainers
class AnonymousVisitorTest {

    /** Under .invalid, which never resolves (RFC 6761). */
    static final String ISSUER = "http://idp.example.invalid";

    private static final String API = "/api/sql-explorer";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.4");

    private static final Path CAPTURES = tempDirectory();

    @DynamicPropertySource
    static void explorerDatabase(DynamicPropertyRegistry registry) {
        registry.add("datasources.explorer.jdbc-url", MYSQL::getJdbcUrl);
        registry.add("datasources.explorer.username", MYSQL::getUsername);
        registry.add("datasources.explorer.password", MYSQL::getPassword);
        registry.add("environments.alpha.default", () -> "true");
        registry.add("environments.beta.default", () -> "false");
        registry.add("parquet-sink.filename", () -> CAPTURES.resolve("sql-repository.parquet").toString());
    }

    private static Path tempDirectory() {
        try {
            Path directory = Files.createTempDirectory("sql-explorer-anonymous");
            directory.toFile().deleteOnExit();
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    JsonMapper json;

    @Autowired
    ClientRegistrationRepository registrations;

    private MockMvc mvc;

    @BeforeEach
    void mockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /** /user says "a visitor", not the legacy admin, and what they may read. */
    @Test
    void userReportsAVisitor() throws Exception {
        JsonNode me = body(get(API + "/user").contextPath(API));
        assertThat(me.path("anonymous").asBoolean()).isTrue();
        assertThat(me.path("mode").asString()).isEqualTo("oidc");
        assertThat(me.path("user").isNull()).isTrue();
        assertThat(me.path("admin").asBoolean()).isFalse();
        assertThat(me.path("grants").valueStream().map(JsonNode::asString).toList()).containsExactly("reader:beta");
        assertThat(me.path("environments").path("beta").valueStream().map(JsonNode::asString).toList())
            .containsExactly("explorer:read");
        assertThat(me.path("environments").has("alpha")).isFalse();
    }

    /** Only the environment visitors may read is listed, and it is the default. */
    @Test
    void environmentsListsOnlyWhatVisitorsMayRead() throws Exception {
        JsonNode list = body(get(API + "/environments").contextPath(API));
        assertThat(list.path("environments").valueStream().map(e -> e.path("name").asString()).toList()).containsExactly("beta");
        assertThat(list.path("defaultEnvironment").asString()).isEqualTo("beta");
    }

    /** Reads there, including the POSTs that only ask; anything more is a 401, which the UI turns into sign-in. */
    @Test
    void aVisitorReadsButAnythingMoreIsSignIn() throws Exception {
        mvc.perform(get(API + "/env/beta/schemas").contextPath(API)).andExpect(status().isOk());
        mvc.perform(post(API + "/env/beta/queries/search").contextPath(API).with(asAngularDoes())
                .contentType("application/json").content("{}"))
            .andExpect(status().isOk());
        // the Servers view's comparison is a read too, though a POST (sqlx-387)
        int compared = mvc.perform(post(API + "/env/beta/topology/compare").contextPath(API).with(asAngularDoes())
                .contentType("application/json").content("{}")).andReturn().getResponse().getStatus();
        assertThat(compared).as("authorized; whatever the comparison itself answers").isNotIn(401, 403);

        mvc.perform(get(API + "/env/alpha/schemas").contextPath(API)).andExpect(status().isUnauthorized());
        mvc.perform(post(API + "/env/beta/notes").contextPath(API).with(asAngularDoes())
                .param("path", "/schema/app_main").param("text", "x"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post(API + "/environments/beta/pull").contextPath(API).with(asAngularDoes()))
            .andExpect(status().isUnauthorized());
        mvc.perform(get(API + "/analysis-failures").contextPath(API)).andExpect(status().isUnauthorized());
        mvc.perform(get(API + "/tokens").contextPath(API)).andExpect(status().isUnauthorized());
    }

    /** Signing in still gives what the groups grant, not the visitor's grants. */
    @Test
    void signedInIsUnchanged() throws Exception {
        var viewer = oidcLogin()
            .clientRegistration(registrations.findByRegistrationId(SecurityConfig.REGISTRATION))
            .idToken(token -> token.issuer(ISSUER).subject("sub-v").claim("email", "v@example.com")
                .claim("groups", List.of("sqlx:viewer:alpha")));
        JsonNode me = json.readTree(mvc.perform(get(API + "/user").contextPath(API).with(viewer))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(me.path("anonymous").asBoolean()).isFalse();
        assertThat(me.path("grants").valueStream().map(JsonNode::asString).toList()).containsExactly("viewer:alpha");
        mvc.perform(post(API + "/env/alpha/notes").contextPath(API).with(viewer).with(asAngularDoes())
                .param("path", "/schema/app_main").param("text", "x"))
            .andExpect(status().is2xxSuccessful());
    }

    private JsonNode body(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** The CSRF token the way Angular's HttpClient sends it (see OidcSignInTest). */
    private static RequestPostProcessor asAngularDoes() {
        return request -> {
            request.setCookies(new Cookie("XSRF-TOKEN", "token-from-the-cookie"));
            request.addHeader("X-XSRF-TOKEN", "token-from-the-cookie");
            return request;
        };
    }
}
