package com.github.safetytrick.sqlexplorer.commentary;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.github.safetytrick.sqlexplorer.auth.UserService;
import org.springframework.stereotype.Service;

@Service
public class AnnotationService {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final int MAX_NAMESPACE = 64;
    private static final int MAX_KEY = 128;
    private static final int MAX_VALUE = 1024;
    private static final Set<String> CONFIDENCES =
        Set.of(Annotation.INFERRED, Annotation.SAMPLED, Annotation.CONFIRMED);

    private final AnnotationRepository repository;
    private final CommentaryEnvironment environment;
    private final UserService userService;
    private final Clock clock;

    AnnotationService(AnnotationRepository repository, CommentaryEnvironment environment,
                      UserService userService, Clock clock) {
        this.repository = repository;
        this.environment = environment;
        this.userService = userService;
        this.clock = clock;
    }

    public record Source(String kind, String name) {
        public static Source rule(String name) {
            return new Source(Annotation.SOURCE_RULE, name);
        }

        public static Source sampler(String name) {
            return new Source(Annotation.SOURCE_SAMPLER, name);
        }

        public static Source person() {
            return new Source(Annotation.SOURCE_PERSON, null);
        }
    }

    public record Statement(TagTarget target, String namespace, String key, String value,
                            String confidence, String basis) {
    }

    public Annotation record(Statement statement, Source source) {
        TagTarget target = statement.target() == null ? null : statement.target().validated();
        if (target == null) {
            throw new IllegalArgumentException("an annotation needs a target");
        }
        requireName("namespace", statement.namespace(), MAX_NAMESPACE);
        requireName("key", statement.key(), MAX_KEY);
        if (statement.value() == null || statement.value().isEmpty()) {
            throw new IllegalArgumentException("an annotation needs a value");
        }
        if (statement.value().length() > MAX_VALUE) {
            throw new IllegalArgumentException("value is longer than " + MAX_VALUE + " characters");
        }
        String confidence = statement.confidence() == null ? Annotation.INFERRED : statement.confidence();
        if (!CONFIDENCES.contains(confidence)) {
            throw new IllegalArgumentException(
                "confidence must be inferred, sampled or confirmed, not " + confidence);
        }

        Annotation annotation = new Annotation();
        annotation.setEnvironmentName(environment.name());
        annotation.target(target);
        annotation.setNamespace(statement.namespace());
        annotation.setKey(statement.key());
        annotation.setValue(statement.value());
        annotation.setConfidence(confidence);
        annotation.setBasis(statement.basis());
        annotation.setSourceKind(source.kind());
        if (Annotation.SOURCE_PERSON.equals(source.kind())) {
            annotation.setAuthor(userService.currentUser());
        } else {
            annotation.setSourceName(source.name());
        }
        annotation.setRecordedOn(clock.instant());
        return repository.save(annotation);
    }

    public Annotation confirm(int id) {
        Annotation confirmed = repository.findByIdAndEnvironmentName(id, environment.name())
            .orElseThrow(() -> new IllegalArgumentException(
                "no annotation " + id + " in " + environment.name()));
        return record(new Statement(confirmed.getTarget(), confirmed.getNamespace(), confirmed.getKey(),
                confirmed.getValue(), Annotation.CONFIRMED, "confirmed annotation " + id),
            Source.person());
    }

    public List<List<Annotation>> currentFor(List<TagTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        List<TagTarget> asked = targets.stream().map(TagTarget::validated).toList();
        String environmentName = environment.name();

        Map<TagTarget, List<Annotation>> found = new HashMap<>();
        for (String type : asked.stream().map(TagTarget::type).distinct().toList()) {
            List<TagTarget> ofType = asked.stream().filter(target -> type.equals(target.type())).toList();
            List<Annotation> rows = switch (type) {
                case TagTarget.ENVIRONMENT -> repository.findAllByEnvironmentNameAndTargetType(environmentName, type);
                case TagTarget.QUERY -> repository.findAllByEnvironmentNameAndTargetTypeAndQueryDigestIn(
                    environmentName, type, ofType.stream().map(TagTarget::digest).distinct().toList());
                default -> repository.findAllByEnvironmentNameAndTargetTypeAndSchemaNameIn(
                    environmentName, type, ofType.stream().map(TagTarget::schema).distinct().toList());
            };
            for (Annotation row : rows) {
                found.computeIfAbsent(row.getTarget(), key -> new ArrayList<>()).add(row);
            }
        }

        List<List<Annotation>> answer = new ArrayList<>(asked.size());
        for (TagTarget target : asked) {
            answer.add(current(found.getOrDefault(target, List.of())));
        }
        return List.copyOf(answer);
    }

    public List<Annotation> current(TagTarget target) {
        return currentFor(List.of(target)).getFirst();
    }

    public List<Annotation> currentInNamespace(String namespace) {
        requireName("namespace", namespace, MAX_NAMESPACE);
        Map<TagTarget, List<Annotation>> byTarget = new LinkedHashMap<>();
        for (Annotation row : repository.findAllByEnvironmentNameAndNamespace(environment.name(), namespace)) {
            byTarget.computeIfAbsent(row.getTarget(), key -> new ArrayList<>()).add(row);
        }
        return byTarget.values().stream().flatMap(rows -> current(rows).stream()).toList();
    }

    public List<Annotation> history(TagTarget target, String namespace, String key) {
        TagTarget wanted = target.validated();
        return rowsOn(wanted).stream()
            .filter(row -> namespace.equals(row.getNamespace()) && key.equals(row.getKey()))
            .sorted(Annotation.LATEST.reversed())
            .toList();
    }

    private List<Annotation> rowsOn(TagTarget target) {
        String environmentName = environment.name();
        List<Annotation> rows = switch (target.type()) {
            case TagTarget.ENVIRONMENT -> repository.findAllByEnvironmentNameAndTargetType(environmentName, target.type());
            case TagTarget.QUERY -> repository.findAllByEnvironmentNameAndTargetTypeAndQueryDigestIn(
                environmentName, target.type(), List.of(target.digest()));
            default -> repository.findAllByEnvironmentNameAndTargetTypeAndSchemaNameIn(
                environmentName, target.type(), List.of(target.schema()));
        };
        return rows.stream().filter(row -> row.getTarget().equals(target)).toList();
    }

    static List<Annotation> current(Collection<Annotation> rows) {
        Map<String, Annotation> latest = new HashMap<>();
        for (Annotation row : rows) {
            latest.merge(row.slot(), row, (a, b) -> Annotation.LATEST.compare(a, b) >= 0 ? a : b);
        }
        return latest.values().stream()
            .sorted(Comparator.comparing(Annotation::getNamespace).thenComparing(Annotation::getKey))
            .toList();
    }

    private static void requireName(String what, String value, int max) {
        if (value == null || !NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " must be lower-case letters, digits, '.', '_' or '-'");
        }
        if (value.length() > max) {
            throw new IllegalArgumentException(what + " is longer than " + max + " characters");
        }
    }
}
