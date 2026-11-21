package com.github.safetytrick.sqlexplorer.commentary;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentScoped;
import java.util.List;
import java.util.function.Supplier;

import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Annotations")
@EnvironmentScoped
@RestController
class AnnotationsResource {

    private final AnnotationService annotations;

    AnnotationsResource(AnnotationService annotations) {
        this.annotations = annotations;
    }

    @PostMapping("/annotations/targets")
    public List<List<Annotation>> forTargets(@RequestBody List<TagTarget> targets) {
        return badRequestOn(() -> annotations.currentFor(targets));
    }

    @GetMapping("/annotations/namespace/{namespace}")
    public List<Annotation> inNamespace(@PathVariable String namespace) {
        return badRequestOn(() -> annotations.currentInNamespace(namespace));
    }

    @PostMapping("/annotations/history")
    public List<Annotation> history(@RequestBody HistoryRequest request) {
        return badRequestOn(() -> {
            if (request.getTarget() == null || request.getNamespace() == null || request.getKey() == null) {
                throw new IllegalArgumentException("history needs a target, a namespace and a key");
            }
            return annotations.history(request.getTarget(), request.getNamespace(), request.getKey());
        });
    }

    @PostMapping("/annotations")
    public Annotation record(@RequestBody RecordRequest request) {
        return badRequestOn(() -> annotations.record(new AnnotationService.Statement(request.getTarget(),
                request.getNamespace(), request.getKey(), request.getValue(),
                request.getConfidence() == null ? Annotation.CONFIRMED : request.getConfidence(),
                request.getBasis()),
            AnnotationService.Source.person()));
    }

    @PostMapping("/annotation/{id}/confirm")
    public Annotation confirm(@PathVariable int id) {
        try {
            return annotations.confirm(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    private static <T> T badRequestOn(Supplier<T> call) {
        try {
            return call.get();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @Data
    public static class HistoryRequest {
        private TagTarget target;
        private String namespace;
        private String key;
    }

    @Data
    public static class RecordRequest {
        private TagTarget target;
        private String namespace;
        private String key;
        private String value;
        private String confidence;
        private String basis;
    }
}
