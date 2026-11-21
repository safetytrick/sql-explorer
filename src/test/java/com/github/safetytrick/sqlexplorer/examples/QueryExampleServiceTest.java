package com.github.safetytrick.sqlexplorer.examples;

import com.github.safetytrick.sqlexplorer.analysis.SqlAnalysisService;
import com.github.safetytrick.sqlexplorer.analysis.parser.NormalizedQueryDigest;
import com.github.safetytrick.sqlexplorer.analysis.parser.QueryNormalizer;
import com.github.safetytrick.sqlexplorer.analysis.parser.QueryParameterizer;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryService;
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

class QueryExampleServiceTest {

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

        SqlRepositoryService sqlRepositoryService = mock(SqlRepositoryService.class);
        when(sqlRepositoryService.getByDigest(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);

        service = new QueryExampleService(repository,
            new NormalizedQueryDigest(new QueryNormalizer(), new SqlParser()),
            new QueryParameterizer(), sqlRepositoryService,
            analysis, fixture.schema(), environmentContext, userService);
    }

    @Test
    void infersTheSchemaWhenTheStatementDeterminesOne() {
        QueryExampleService.Result result =
            service.create("select id, name from members where id = 7", null, null);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.stored().getSessionSchema()).isEqualTo("app");
        assertThat(result.stored().isSchemaInferred()).isTrue();
    }

    @Test
    void reportsCandidateSchemasWhenTheStatementIsAmbiguous() {
        QueryExampleService.Result result =
            service.create("select id from orders where total > 100", null, null);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.failure()).isEqualTo(QueryExampleService.Failure.AMBIGUOUS_SCHEMA);
        assertThat(result.candidateSchemas()).containsExactlyInAnyOrder("app", "archive");
    }

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

    @Test
    void derivesTheSameDigestForTheSameStatementAndSchema() {
        QueryExampleService.Result first =
            service.create("select id from orders where total > 100", "app", null);
        QueryExampleService.Result second =
            service.create("select id from orders where total > 100", "app", null);

        assertThat(first.stored().getNormalizedDigest())
            .isEqualTo(second.stored().getNormalizedDigest());
    }

    @Test
    void reachesTheSameDigestWhateverTheParametersWere() {
        QueryExampleService.Result hundred =
            service.create("select id from orders where total > 100", "app", null);
        QueryExampleService.Result nineNineNine =
            service.create("select id from orders where total > 999", "app", null);
        QueryExampleService.Result asTheCorpusHoldsIt =
            service.create("select id from orders where total > ?", "app", null);

        assertThat(hundred.stored().getNormalizedDigest())
            .isEqualTo(nineNineNine.stored().getNormalizedDigest())
            .isEqualTo(asTheCorpusHoldsIt.stored().getNormalizedDigest());
    }

    @Test
    void reachesTheSameDigestWhicheverWayIdentifiersAreWritten() {
        QueryExampleService.Result human =
            service.create("select id from orders where total > 100", "app", null);
        QueryExampleService.Result mysqlStyle =
            service.create("SELECT `id` FROM `orders` WHERE `total` > ?", "app", null);

        assertThat(human.stored().getNormalizedDigest())
            .isEqualTo(mysqlStyle.stored().getNormalizedDigest());
    }

    @Test
    void reachesTheSameDigestForAnInListHoweverManyElements() {
        QueryExampleService.Result three =
            service.create("select id from orders where id in (1, 2, 3)", "app", null);
        QueryExampleService.Result one =
            service.create("select id from orders where id in (7)", "app", null);

        assertThat(three.stored().getNormalizedDigest())
            .isEqualTo(one.stored().getNormalizedDigest());
    }

    @Test
    void takesTheEnvironmentFromTheActiveContextRatherThanTheRequest() {
        QueryExampleService.Result result =
            service.create("select id, name from members", null, null);

        assertThat(result.stored().getEnvironmentName()).isEqualTo("regression");
    }

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
     * never null, so a freshly built example must report itself new or the
     * first save is an UPDATE against a row that does not exist.
     */
    @Test
    void aFreshlyBuiltExampleIsNewSoAPrePopulatedIdStillInserts() {
        QueryExample example = service.create("select id from members", "app", null).stored();

        assertThat(example.isNew()).isTrue();
    }

    @Test
    void anExampleLoadedFromTheDatabaseIsNotNew() {
        QueryExample loaded = new QueryExample();
        loaded.markPersisted();

        assertThat(loaded.isNew()).isFalse();
    }

    private static String uuidBytes(java.util.UUID uuid) {
        return String.format("%016x%016x", uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
    }

}
