package com.github.safetytrick.sqlexplorer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentScoped;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application starts: every bean can be built and wired, the Liquibase
 * changelog runs, and the JPA mappings agree with the tables it makes. And,
 * over real HTTP, the MCP endpoint answers — it is a plain servlet beside the
 * DispatcherServlet, so only a running server shows it is mapped and wired.
 *
 * <p>Nothing else boots the whole context, so without this a bean Spring
 * cannot construct passes the build and fails only when the jar starts — which
 * is how a resource with two unannotated constructors reached a running
 * container. The explorer database is MySQL and its changelog is MySQL's own
 * SQL, so it runs against a real one rather than an in-memory stand-in. No
 * environments are configured, so nothing is pulled or connected at startup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ApplicationContextTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.4");

    /** The explorer's own data source, which binds {@code datasources.explorer.*} rather than Boot's. */
    @DynamicPropertySource
    static void explorerDatabase(DynamicPropertyRegistry registry) {
        registry.add("datasources.explorer.jdbc-url", MYSQL::getJdbcUrl);
        registry.add("datasources.explorer.username", MYSQL::getUsername);
        registry.add("datasources.explorer.password", MYSQL::getPassword);
        // two environments with nothing in them: enough to show a tool reads the one it is asked for
        registry.add("environments.alpha.default", () -> "true");
        registry.add("environments.beta.default", () -> "false");
        // captures, schema snapshots and reports are written beside this
        registry.add("parquet-sink.filename", () -> CAPTURES.resolve("sql-repository.parquet").toString());
    }

    private static final Path CAPTURES = tempDirectory();

    private static Path tempDirectory() {
        try {
            Path directory = Files.createTempDirectory("sql-explorer-context");
            directory.toFile().deleteOnExit();
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    ApplicationContext context;

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper jsonMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void starts() {
        assertThat(context.getBeanDefinitionCount()).isPositive();
    }

    @Test
    void mcpListsEveryToolWithItsArguments() throws Exception {
        JsonNode tools = mcp("tools/list", "{}", null).path("result").path("tools");

        assertThat(tools.valueStream().map(tool -> tool.path("name").asString()).toList()).containsExactlyInAnyOrder(
            "list_schemas", "list_tables", "schema_tree", "get_table_structure", "get_table_ddl", "get_foreign_keys",
            "get_table_stats", "get_column_usage", "get_graph", "search_queries", "get_query", "run_report",
            "list_tags", "get_queries_by_tag", "add_tag", "remove_tag", "clean_sql", "normalize_sql",
            "ddl_preflight");
        JsonNode addTag = tools.valueStream().filter(tool -> tool.path("name").asString().equals("add_tag")).findFirst().orElseThrow();
        assertThat(addTag.path("annotations").path("readOnlyHint").asBoolean()).isFalse();
        assertThat(addTag.path("inputSchema").path("properties").has("environment")).isTrue();
        assertThat(addTag.path("inputSchema").path("required").toString()).contains("tag");
    }

    @Test
    void mcpCallsATool() throws Exception {
        JsonNode result = mcp("tools/call", "{\"name\":\"normalize_sql\",\"arguments\":{\"sql\":\"select a from t where id in (1, 2, 3)\"}}", null)
            .path("result");

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("content").get(0).path("text").asString()).containsIgnoringCase("select");
    }

    /** An environment a tool names but nobody configured is refused, as the REST API refuses it with a 404. */
    @Test
    void mcpRefusesAnEnvironmentItDoesNotKnow() throws Exception {
        JsonNode result = mcp("tools/call", "{\"name\":\"list_schemas\",\"arguments\":{\"environment\":\"nope\"}}", null)
            .path("result");

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asString())
            .contains("no environment \"nope\"").contains("alpha, beta");
    }

    /** A tool reads the environment it names, through the same request scope a REST call uses. */
    @Test
    void mcpReadsTheEnvironmentItIsAskedFor() throws Exception {
        JsonNode result = mcp("tools/call", "{\"name\":\"list_schemas\",\"arguments\":{\"environment\":\"beta\"}}", null)
            .path("result");

        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        assertThat(result.path("content").get(0).path("text").asString()).isEqualTo("[ ]");
    }

    /** The environment is a path segment, so the URL alone says which corpus a request reads (sqlx-e13.6). */
    @Test
    void restReadsTheEnvironmentInItsPath() throws Exception {
        HttpResponse<String> beta = get("/env/beta/schemas");
        assertThat(beta.statusCode()).as(beta.body()).isEqualTo(200);
        assertThat(jsonMapper.readTree(beta.body()).isArray()).isTrue();
    }

    /** No silent answer from the default environment: an unknown name is not found. */
    @Test
    void restRefusesAnEnvironmentItDoesNotKnow() throws Exception {
        assertThat(get("/env/nope/schemas").statusCode()).isEqualTo(404);
    }

    /** The old unprefixed paths are gone rather than quietly reading the default environment. */
    @Test
    void restNoLongerServesEnvironmentScopedPathsWithoutAnEnvironment() throws Exception {
        assertThat(get("/schemas").statusCode()).isEqualTo(404);
        assertThat(get("/environments").statusCode()).isEqualTo(200);
    }

    /**
     * Every endpoint is either under /env/{env}/ or deliberately global. A new
     * controller has to choose: forgetting @EnvironmentScoped would serve an
     * environment's data on a path authorization cannot read a scope from.
     */
    @Test
    void everyControllerIsEnvironmentScopedOrKnownGlobal() {
        Set<String> global = Set.of("UserResource", "QueryCleanupResource", "AnalysisFailureResource",
            "EnvironmentResource", "GraphLayoutResource", "LoginResource", "AccessTokenResource");
        Set<String> unscoped = new TreeSet<>();
        context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class).getHandlerMethods()
            .forEach((info, method) -> {
                Class<?> type = method.getBeanType();
                if (!type.getPackageName().startsWith("com.github.safetytrick.sqlexplorer")) { return; }
                boolean scoped = AnnotatedElementUtils.hasAnnotation(type, EnvironmentScoped.class);
                if (scoped) {
                    assertThat(info.getPatternValues()).allMatch(p -> p.startsWith(EnvironmentScoped.PREFIX + "/"));
                } else if (!global.contains(type.getSimpleName())) {
                    unscoped.add(type.getSimpleName());
                }
            });
        assertThat(unscoped).as("controllers neither @EnvironmentScoped nor known global").isEmpty();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sql-explorer" + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    /** A client's config can pin its environment with a header; an argument still wins. */
    @Test
    void mcpReadsTheEnvironmentItsConfigPins() throws Exception {
        HttpResponse<String> pinned = post("tools/call", "{\"name\":\"list_schemas\",\"arguments\":{}}", null, "nope");
        assertThat(pinned.body()).contains("no environment \\\"nope\\\"");
        HttpResponse<String> overridden = post("tools/call", "{\"name\":\"list_schemas\",\"arguments\":{\"environment\":\"beta\"}}", null, "nope");
        assertThat(jsonMapper.readTree(overridden.body()).path("result").path("isError").asBoolean()).as(overridden.body()).isFalse();
    }

    /**
     * Sign-in is off by default (sqlx-e13.1): no session needed, and everyone
     * is the legacy admin. OidcSignInTest covers it on.
     */
    @Test
    void withSignInOffEveryoneIsTheLegacyAdmin() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
            URI.create("http://localhost:" + port + "/api/sql-explorer/user")).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode user = jsonMapper.readTree(response.body());
        assertThat(user.path("mode").asString()).isEqualTo("none");
        assertThat(user.path("user").path("email").asString()).isEqualTo("admin");
        assertThat(user.path("groups").isEmpty()).isTrue();
    }

    /** A page on another site cannot drive it through someone's localhost. */
    @Test
    void mcpRefusesAForeignOrigin() throws Exception {
        assertThat(post("tools/list", "{}", "https://attacker.example.com").statusCode()).isEqualTo(403);
        assertThat(post("tools/list", "{}", "http://localhost:4200").statusCode()).isEqualTo(200);
    }

    /**
     * Nor through a name rebound to this machine, though that page is its own
     * origin: with sign-in off only localhost is trusted (McpOriginValidator).
     */
    @Test
    void mcpRefusesAReboundNameWithSignInOff() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sql-explorer/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("Origin", "https://rebound.example.test")
            .header("X-Forwarded-Proto", "https")
            .header("X-Forwarded-Host", "rebound.example.test")
            .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"))
            .build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }

    private JsonNode mcp(String method, String params, String origin) throws Exception {
        HttpResponse<String> response = post(method, params, origin);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return jsonMapper.readTree(response.body());
    }

    private HttpResponse<String> post(String method, String params, String origin) throws Exception {
        return post(method, params, origin, null);
    }

    private HttpResponse<String> post(String method, String params, String origin, String environment) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sql-explorer/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}"));
        if (origin != null) { request.header("Origin", origin); }
        if (environment != null) { request.header("X-Sql-Explorer-Environment", environment); }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
