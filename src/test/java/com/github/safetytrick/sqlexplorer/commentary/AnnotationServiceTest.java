package com.github.safetytrick.sqlexplorer.commentary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.github.safetytrick.sqlexplorer.auth.User;
import com.github.safetytrick.sqlexplorer.auth.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Annotations are appended, read back latest-first per (namespace, key), and
 * scoped to the selected environment (sqlx-n5j.13).
 */
class AnnotationServiceTest {

    private static final String ENV = "regression";
    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

    private AnnotationRepository repository;
    private AnnotationService service;
    private final User person = new User();

    @BeforeEach
    void setUp() {
        repository = mock(AnnotationRepository.class);
        CommentaryEnvironment environment = mock(CommentaryEnvironment.class);
        when(environment.name()).thenReturn(ENV);
        UserService users = mock(UserService.class);
        person.setEmail("reader@example.com");
        when(users.currentUser()).thenReturn(person);
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        service = new AnnotationService(repository, environment, users, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Annotation row(int id, TagTarget target, String namespace, String key, String value,
                                  String confidence, Instant recordedOn) {
        Annotation annotation = new Annotation();
        annotation.setId(id);
        annotation.setEnvironmentName(ENV);
        annotation.target(target);
        annotation.setNamespace(namespace);
        annotation.setKey(key);
        annotation.setValue(value);
        annotation.setConfidence(confidence);
        annotation.setSourceKind(Annotation.SOURCE_RULE);
        annotation.setRecordedOn(recordedOn);
        return annotation;
    }

    @Test
    void aRuleRecordsWithItsNameAndNoAuthor() {
        Annotation saved = service.record(new AnnotationService.Statement(TagTarget.ofColumn("app", "account", "status"),
            "classification", "kind", "enum", null, "{\"distinct\":4}"), AnnotationService.Source.rule("low-cardinality"));

        assertThat(saved.getEnvironmentName()).isEqualTo(ENV);
        assertThat(saved.getTarget()).isEqualTo(TagTarget.ofColumn("app", "account", "status"));
        assertThat(saved.getConfidence()).isEqualTo(Annotation.INFERRED);
        assertThat(saved.getSourceKind()).isEqualTo("rule");
        assertThat(saved.getSourceName()).isEqualTo("low-cardinality");
        assertThat(saved.getAuthor()).isNull();
        assertThat(saved.getRecordedOn()).isEqualTo(NOW);
    }

    @Test
    void aPersonIsTheAuthor() {
        Annotation saved = service.record(new AnnotationService.Statement(TagTarget.ofSchema("mysql"),
            "schema-role", "role", "system", Annotation.CONFIRMED, null), AnnotationService.Source.person());

        assertThat(saved.getSourceKind()).isEqualTo("person");
        assertThat(saved.getAuthor()).isSameAs(person);
        assertThat(saved.getSourceName()).isNull();
    }

    @Test
    void aStatementThatSaysNothingIsRefused() {
        TagTarget table = TagTarget.ofTable("app", "account");
        assertThatThrownBy(() -> service.record(new AnnotationService.Statement(table, "Bad Space", "k", "v", null, null),
            AnnotationService.Source.person())).hasMessageContaining("namespace");
        assertThatThrownBy(() -> service.record(new AnnotationService.Statement(table, "ns", "k", "", null, null),
            AnnotationService.Source.person())).hasMessageContaining("value");
        assertThatThrownBy(() -> service.record(new AnnotationService.Statement(table, "ns", "k", "v", "certain", null),
            AnnotationService.Source.person())).hasMessageContaining("confidence");
        assertThatThrownBy(() -> service.record(new AnnotationService.Statement(new TagTarget("table", "app", "", ""),
            "ns", "k", "v", null, null), AnnotationService.Source.person())).hasMessageContaining("needs a table");
    }

    /** History is kept; the newest row for a (namespace, key) is the answer, on its own target only. */
    @Test
    void theLatestRowWinsPerKeyAndTargetsAreAnsweredPositionally() {
        TagTarget status = TagTarget.ofColumn("app", "account", "status");
        TagTarget name = TagTarget.ofColumn("app", "account", "name");
        when(repository.findAllByEnvironmentNameAndTargetTypeAndSchemaNameIn(ENV, "column", List.of("app")))
            .thenReturn(List.of(
                row(1, status, "classification", "kind", "text", Annotation.INFERRED, NOW.minusSeconds(60)),
                row(2, status, "classification", "kind", "enum", Annotation.CONFIRMED, NOW),
                row(3, status, "data-profile", "distinct", "4", Annotation.SAMPLED, NOW.minusSeconds(30)),
                row(4, name, "classification", "kind", "text", Annotation.INFERRED, NOW)));

        List<List<Annotation>> answer = service.currentFor(List.of(name, status, TagTarget.ofColumn("app", "site", "id")));

        assertThat(answer).hasSize(3);
        assertThat(answer.get(0)).extracting(Annotation::getValue).containsExactly("text");
        assertThat(answer.get(1)).extracting(Annotation::getId).containsExactly(2, 3);
        assertThat(answer.get(2)).isEmpty();
    }

    @Test
    void historyIsNewestFirst() {
        TagTarget status = TagTarget.ofColumn("app", "account", "status");
        when(repository.findAllByEnvironmentNameAndTargetTypeAndSchemaNameIn(ENV, "column", List.of("app")))
            .thenReturn(List.of(
                row(1, status, "classification", "kind", "text", Annotation.INFERRED, NOW.minusSeconds(60)),
                row(2, status, "classification", "kind", "enum", Annotation.CONFIRMED, NOW)));

        assertThat(service.history(status, "classification", "kind")).extracting(Annotation::getId).containsExactly(2, 1);
    }

    @Test
    void confirmingAppendsTheSameValueAsThePersonsConfirmedAnswer() {
        TagTarget status = TagTarget.ofColumn("app", "account", "status");
        when(repository.findByIdAndEnvironmentName(7, ENV)).thenReturn(Optional.of(
            row(7, status, "classification", "kind", "enum", Annotation.INFERRED, NOW.minusSeconds(60))));

        service.confirm(7);

        ArgumentCaptor<Annotation> saved = ArgumentCaptor.forClass(Annotation.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getId()).isNull();
        assertThat(saved.getValue().getValue()).isEqualTo("enum");
        assertThat(saved.getValue().getConfidence()).isEqualTo(Annotation.CONFIRMED);
        assertThat(saved.getValue().getAuthor()).isSameAs(person);
        assertThat(saved.getValue().getBasis()).contains("7");
    }

    @Test
    void confirmingAnotherEnvironmentsRowIsRefused() {
        when(repository.findByIdAndEnvironmentName(7, ENV)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(7)).hasMessageContaining(ENV);
    }

    @Test
    void theEnvironmentTargetReadsEveryEnvironmentRow() {
        when(repository.findAllByEnvironmentNameAndTargetType(ENV, "environment")).thenReturn(List.of(
            row(1, TagTarget.ofEnvironment(), "generator", "doctrine-orm", "used", Annotation.CONFIRMED, NOW)));

        assertThat(service.current(TagTarget.ofEnvironment())).extracting(Annotation::getKey)
            .containsExactly("doctrine-orm");
    }
}
