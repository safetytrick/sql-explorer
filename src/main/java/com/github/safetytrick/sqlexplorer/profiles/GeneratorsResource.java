package com.github.safetytrick.sqlexplorer.profiles;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.github.safetytrick.sqlexplorer.analysis.GeneratorSignatures;
import com.github.safetytrick.sqlexplorer.commentary.Annotation;
import com.github.safetytrick.sqlexplorer.commentary.AnnotationService;
import com.github.safetytrick.sqlexplorer.commentary.TagTarget;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The generators this build can recognise, and which ones a person has
 * confirmed the selected environment uses (sqlx-n5j.12).
 *
 * <p>A confirmation is an annotation on the environment target: namespace
 * {@value #NAMESPACE}, key the generator's name, value {@value #USED} or
 * {@value #NOT_USED}, confidence confirmed. Latest wins and history is kept,
 * so withdrawing a confirmation is another row rather than a delete. A query's
 * candidate becomes its asserted origin only for a generator confirmed here,
 * and that is decided when the query is read, so confirming needs no
 * re-analysis.
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Generators")
@RestController
class GeneratorsResource {

    static final String NAMESPACE = "generator";
    static final String USED = "used";
    static final String NOT_USED = "not-used";

    private final AnnotationService annotations;

    GeneratorsResource(AnnotationService annotations) {
        this.annotations = annotations;
    }

    /** One generator: its signatures, and what this environment has said about it. */
    public record GeneratorView(String name, String description, List<RuleView> rules,
                                String status, Annotation confirmation) {
    }

    public record RuleView(String id, String description) {
    }

    @GetMapping("/generators")
    public List<GeneratorView> generators() {
        Map<String, Annotation> confirmations = annotations.current(TagTarget.ofEnvironment()).stream()
            .filter(annotation -> NAMESPACE.equals(annotation.getNamespace()))
            .collect(Collectors.toMap(Annotation::getKey, Function.identity()));
        return GeneratorSignatures.GENERATORS.stream().map(generator -> {
            Annotation confirmation = confirmations.get(generator.name());
            return new GeneratorView(generator.name(), generator.description(),
                generator.rules().stream().map(rule -> new RuleView(rule.id(), rule.description())).toList(),
                confirmation == null ? null : confirmation.getValue(), confirmation);
        }).toList();
    }

    /** A person states whether this environment uses a generator. */
    @PostMapping("/generators/{name}/confirmation")
    public Annotation confirm(@PathVariable String name, @RequestBody Confirmation confirmation) {
        if (!GeneratorSignatures.known(name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no generator " + name);
        }
        boolean used = confirmation != null && confirmation.isUsed();
        return annotations.record(new AnnotationService.Statement(TagTarget.ofEnvironment(), NAMESPACE, name,
                used ? USED : NOT_USED, Annotation.CONFIRMED,
                used ? "a person confirmed this environment's statements include " + name + "'s"
                    : "a person stated this environment does not use " + name),
            AnnotationService.Source.person());
    }

    @Data
    public static class Confirmation {
        private boolean used;
    }
}
