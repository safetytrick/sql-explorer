package com.github.safetytrick.sqlexplorer.profiles;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentScoped;
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

@io.swagger.v3.oas.annotations.tags.Tag(name = "Generators")
@EnvironmentScoped
@RestController
class GeneratorsResource {

    static final String NAMESPACE = "generator";
    static final String USED = "used";
    static final String NOT_USED = "not-used";

    private final AnnotationService annotations;

    GeneratorsResource(AnnotationService annotations) {
        this.annotations = annotations;
    }

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
