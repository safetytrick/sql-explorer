package com.github.safetytrick.sqlexplorer.examples;

import com.github.safetytrick.sqlexplorer.analysis.SqlAnalysisService;
import com.github.safetytrick.sqlexplorer.analysis.parser.NormalizedQueryDigest;
import com.github.safetytrick.sqlexplorer.analysis.parser.QueryNormalizer;
import com.github.safetytrick.sqlexplorer.analysis.parser.SqlParser;
import com.github.safetytrick.sqlexplorer.auth.User;
import com.github.safetytrick.sqlexplorer.auth.UserService;
import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;
import com.github.safetytrick.sqlexplorer.analysis.SchemaFixture;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The write path, against a real two-schema fixture and the real
 * analyzer chain — the inference under test is the analyzers', so mocking it
 * would test nothing.
 */
class QueryExampleServiceTest {

    /**
     * Two schemas, and `orders` exists in both. That collision is the whole
     * point: an unqualified `orders` cannot be resolved to one schema, which is
     * the ambiguity the endpoint has to report rather than guess at.
     */
    private static final String DDL = """
        create table app.orders (id int, member_id int, total int, primary key (id));
        create table archive.orders (id int, member_id int, total int, primary key (id));
        create table app.members (id int, name varchar(50), primary key (id));
        """;

    private QueryExampleService service;
    private QueryExampleRepository repository;

    @BeforeEach
    void setUp() throws JSQLParserException {
        SchemaFixture fixture = SchemaFixture.fromSql(DDL);
        SqlAnalysisService analysis = fixture.analysisServiceWithSchemaInference();

        repository = mock(QueryExampleRepository.class);
        when(repository.save(any(QueryExample.class))).thenAnswer(inv -> inv.getArgument(0));

        Environment environment = mock(Environment.class);
        when(environment.getName()).thenReturn("regression");
        EnvironmentContext environmentContext = mock(EnvironmentContext.class);
        when(environmentContext.current()).thenReturn(environment);

        UserService userService = mock(UserService.class);
        when(userService.currentUser()).thenReturn(new User());

        service = new QueryExampleService(repository,
            new NormalizedQueryDigest(new QueryNormalizer(), new SqlParser()),
            analysis, fixture.schema(), environmentContext, userService);
    }

    // ── the schema the digest is scoped by ──

    @Test
    void infersTheSchemaWhenTheStatementDeterminesOne() {
        QueryExampleService.Result result =
            service.create("select id, name from members where id = 7", null, null);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.stored().getSessionSchema()).isEqualTo("app");
        assertThat(result.stored().isSchemaInferred()).isTrue();
    }

    /**
     * The discriminating case. `orders` is in both schemas, so nothing in the
     * statement says which — the caller gets both back and picks.
     */
    @Test
    void reportsCandidateSchemasWhenTheStatementIsAmbiguous() {
        QueryExampleService.Result result =
            service.create("select id from orders where total > 100", null, null);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.failure()).isEqualTo(QueryExampleService.Failure.AMBIGUOUS_SCHEMA);
        assertThat(result.candidateSchemas()).containsExactlyInAnyOrder("app", "archive");
    }

    /** Resubmitting with one of the offered candidates is what resolves it. */
    @Test
    void acceptsTheStatementOnceOneOfTheCandidatesIsSupplied() {
        QueryExampleService.Result ambiguous =
            service.create("select id from orders where total > 100", null, null);
        String chosen = ambiguous.candidateSchemas().getFirst();

        QueryExampleService.Result result =
            service.create("select id from orders where total > 100", chosen, null);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.stored().getSessionSchema()).isEqualTo(chosen);
        assertThat(result.stored().isSchemaInferred()).isFalse();
    }

    /** A supplied schema skips inference rather than competing with it. */
    @Test
    void believesASuppliedSchemaEvenWhereInferenceWouldHaveSucceeded() {
        QueryExampleService.Result result =
            service.create("select id, name from members where id = 7", "archive", null);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.stored().getSessionSchema()).isEqualTo("archive");
        assertThat(result.stored().isSchemaInferred()).isFalse();
    }

    @Test
    void reportsNoMatchingSchemaWhenNothingContainsTheTables() {
        QueryExampleService.Result result =
            service.create("select id from nowhere_at_all where id = 1", null, null);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.failure()).isEqualTo(QueryExampleService.Failure.NO_MATCHING_SCHEMA);
        assertThat(result.candidateSchemas()).isEmpty();
    }

    @Test
    void rejectsAStatementThatCannotBeParsed() {
        QueryExampleService.Result result =
            service.create("this is not sql at all", null, null);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.failure()).isEqualTo(QueryExampleService.Failure.UNPARSEABLE);
        assertThat(result.detail()).isNotBlank();
    }

    // ── the digest is derived, never accepted ──

    /**
     * The rule the whole feature rests on. Nothing in the request names a
     * digest; the same statement in the same schema always lands on the same
     * one, and that is what makes the association trustworthy.
     */
    @Test
    void derivesTheSameDigestForTheSameStatementAndSchema() {
        QueryExampleService.Result first =
            service.create("select id from orders where total > 100", "app", null);
        QueryExampleService.Result second =
            service.create("select id from orders where total > 100", "app", null);

        assertThat(first.stored().getNormalizedDigest())
            .isEqualTo(second.stored().getNormalizedDigest());
    }

    /**
     * KNOWN GAP, pinned deliberately so it cannot be discovered twice.
     *
     * <p>`QueryNormalizer` does not strip literals — its javadoc is explicit
     * that "distinct literals stay distinct", and it is right to be: the corpus
     * text arrives from Performance Schema with MySQL's own `?` placeholders
     * already in place, and this normalizer is a second pass over that.
     *
     * <p>So two examples differing only in their parameters digest differently,
     * and — the part that matters — an example carrying real parameters digests
     * differently from the profile it is an example *of*, whose text reads
     * `total > ?`. Until a parameterization step exists, a stored example keys
     * to a digest no profile has.
     *
     * <p>When that step lands, this test should inverted: both of these should
     * produce the same digest, and it should equal the profile's.
     */
    @Test
    void distinctLiteralsStillDigestDistinctly_theParameterizationGap() {
        QueryExampleService.Result hundred =
            service.create("select id from orders where total > 100", "app", null);
        QueryExampleService.Result nineNineNine =
            service.create("select id from orders where total > 999", "app", null);
        QueryExampleService.Result parameterized =
            service.create("select id from orders where total > ?", "app", null);

        assertThat(hundred.stored().getNormalizedDigest())
            .isNotEqualTo(nineNineNine.stored().getNormalizedDigest());
        // and neither matches the shape the corpus actually stores
        assertThat(hundred.stored().getNormalizedDigest())
            .isNotEqualTo(parameterized.stored().getNormalizedDigest());
    }

    /**
     * The same text in two schemas is two groups, not one — `scopedDigest` keys
     * by session schema deliberately. If this ever fails, examples from
     * different schemas have started merging.
     */
    @Test
    void derivesDifferentDigestsForTheSameStatementInDifferentSchemas() {
        QueryExampleService.Result inApp =
            service.create("select id from orders where total > 100", "app", null);
        QueryExampleService.Result inArchive =
            service.create("select id from orders where total > 100", "archive", null);

        assertThat(inApp.stored().getNormalizedDigest())
            .isNotEqualTo(inArchive.stored().getNormalizedDigest());
    }

    /** Literals are what the normalization strips; the stored text keeps them. */
    @Test
    void storesTheStatementWithItsParametersIntact() {
        String sql = "select id from orders where total > 100 and member_id = 42";

        QueryExampleService.Result result = service.create(sql, "app", "from the slow log");

        assertThat(result.stored().getQueryText()).isEqualTo(sql);
        assertThat(result.stored().getDescription()).isEqualTo("from the slow log");
    }

    // ── derived rather than accepted, continued ──

    @Test
    void takesTheEnvironmentFromTheActiveContextRatherThanTheRequest() {
        QueryExampleService.Result result =
            service.create("select id, name from members", null, null);

        assertThat(result.stored().getEnvironmentName()).isEqualTo("regression");
    }

    /**
     * A UUIDv7 id is assigned before the row is saved — it is not database
     * generated — and it must be time-ordered, because the retrieval API relies
     * on `order by id desc` meaning most-recent-first.
     */
    @Test
    void assignsATimeOrderedIdBeforeSaving() throws InterruptedException {
        QueryExample first = service.create("select id from orders", "app", null).stored();
        Thread.sleep(2);
        QueryExample second = service.create("select id from members", "app", null).stored();

        assertThat(first.getId()).isNotNull();
        assertThat(second.getId()).isNotNull();
        assertThat(first.getId().version()).isEqualTo(7);
        assertThat(first.getId()).isNotEqualTo(second.getId());
        // v7 puts the timestamp in the high bits, so the later id sorts after
        assertThat(uuidBytes(second.getId())).isGreaterThan(uuidBytes(first.getId()));
    }

    /**
     * Hibernate decides insert-versus-update from a null id, and this id is
     * never null. Without {@code Persistable.isNew()} the first save would
     * attempt an update against a row that does not exist.
     */
    @Test
    void isAlwaysNewSoAPrePopulatedIdStillInserts() {
        QueryExample example = service.create("select id from members", "app", null).stored();

        assertThat(example.isNew()).isTrue();
    }

    private static String uuidBytes(java.util.UUID uuid) {
        return String.format("%016x%016x", uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
    }

}
