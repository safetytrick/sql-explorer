package com.github.safetytrick.sqlexplorer.source;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SourceRefResource {

    private final SourceRefEntityRepository sourceRefEntityRepository;

    public SourceRefResource(SourceRefEntityRepository sourceRefEntityRepository) {
        this.sourceRefEntityRepository = sourceRefEntityRepository;
    }

    @Cacheable("sourceref")
    @GetMapping("/api/sql-explorer/sourceref")
    public Iterable<SourceRefEntity> listSourceRefs() {
        return sourceRefEntityRepository.findAll();
    }

}
