package com.github.safetytrick.sqlexplorer.evidence.source;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name="Source")
@RestController
class SourceRefResource {

    private final SourceRefEntityRepository sourceRefEntityRepository;

    public SourceRefResource(SourceRefEntityRepository sourceRefEntityRepository) {
        this.sourceRefEntityRepository = sourceRefEntityRepository;
    }

    @Cacheable("sourceref")
    @GetMapping("/sourceref")
    public Iterable<SourceRefEntity> listSourceRefs() {
        return sourceRefEntityRepository.findAll();
    }

}
