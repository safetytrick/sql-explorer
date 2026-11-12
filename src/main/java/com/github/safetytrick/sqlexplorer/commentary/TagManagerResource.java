package com.github.safetytrick.sqlexplorer.commentary;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentScoped;
import java.util.List;

import com.github.safetytrick.sqlexplorer.commentary.TagManagerService.TagSummary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Tags")
@EnvironmentScoped
@RestController
class TagManagerResource {

    private final TagManagerService tagManagerService;

    TagManagerResource(TagManagerService tagManagerService) {
        this.tagManagerService = tagManagerService;
    }

    /** Every tag name with its color and usage counts (definitions and legacy assignment-only tags). */
    @GetMapping("/tags")
    public List<TagSummary> listTags() {
        return tagManagerService.getSummaries();
    }

    @PutMapping("/tags/definitions/{name}")
    public TagDefinition define(@PathVariable String name, @RequestBody DefineTagRequest body) {
        String nameProblem = Names.tagNameProblem(name);
        if (nameProblem != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, nameProblem);
        }
        return tagManagerService.define(name, body.color());
    }

    /** Deletes the definition and every assignment of the tag. */
    @DeleteMapping("/tags/definitions/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        tagManagerService.delete(name);
        return ResponseEntity.noContent().build();
    }

    record DefineTagRequest(String color) { }
}
