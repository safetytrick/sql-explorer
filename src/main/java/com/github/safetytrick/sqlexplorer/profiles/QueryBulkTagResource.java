package com.github.safetytrick.sqlexplorer.profiles;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentScoped;
import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.commentary.BulkTagService;
import com.github.safetytrick.sqlexplorer.commentary.TagTarget;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Tags")
@EnvironmentScoped
@RestController
class QueryBulkTagResource {

    private final QuerySearchService querySearchService;
    private final BulkTagService bulkTagService;

    QueryBulkTagResource(QuerySearchService querySearchService, BulkTagService bulkTagService) {
        this.querySearchService = querySearchService;
        this.bulkTagService = bulkTagService;
    }

    public record SearchTagPreview(int count, int limit, boolean capped) { }

    @PostMapping("/tags/search/preview")
    public SearchTagPreview preview(@RequestBody(required = false) QuerySearchRequest request) {
        int count = targets(request).size();
        return new SearchTagPreview(count, BulkTagService.MAX_PATHS, count > BulkTagService.MAX_PATHS);
    }

    @PostMapping("/tags/search")
    public BulkTagService.BulkResult tagSearch(@RequestBody SearchTag request) {
        List<TagTarget> targets = targets(request.getSearch());
        return request.isRemove()
            ? bulkTagService.detach(request.getName(), targets)
            : bulkTagService.attach(request.getName(), targets, QUERY_TYPE);
    }

    private List<TagTarget> targets(QuerySearchRequest request) {
        return querySearchService.matchingProfiles(request).stream()
            .map(SqlProfile::getDigest)
            .filter(digest -> digest != null && !digest.isBlank())
            .distinct()
            .map(TagTarget::ofQuery)
            .toList();
    }

    private static final String QUERY_TYPE = "query";

    @Data
    public static class SearchTag {
        private String name;
        private QuerySearchRequest search;
        private boolean remove;
    }
}
