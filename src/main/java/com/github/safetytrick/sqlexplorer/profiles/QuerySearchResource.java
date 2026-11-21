package com.github.safetytrick.sqlexplorer.profiles;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentScoped;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Query")
@EnvironmentScoped
@RestController
class QuerySearchResource {

    private final QuerySearchService querySearchService;

    QuerySearchResource(QuerySearchService querySearchService) {
        this.querySearchService = querySearchService;
    }

    @PostMapping("/queries/search")
    public QuerySearchResult search(@RequestBody(required = false) QuerySearchRequest request) {
        return querySearchService.search(request);
    }
}
