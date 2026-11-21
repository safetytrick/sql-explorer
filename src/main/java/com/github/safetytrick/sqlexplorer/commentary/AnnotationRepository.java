package com.github.safetytrick.sqlexplorer.commentary;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

interface AnnotationRepository extends CrudRepository<Annotation, Integer> {

    List<Annotation> findAllByEnvironmentNameAndTargetTypeAndSchemaNameIn(
        String environmentName, String targetType, List<String> schemaNames);

    List<Annotation> findAllByEnvironmentNameAndTargetTypeAndQueryDigestIn(
        String environmentName, String targetType, List<String> digests);

    List<Annotation> findAllByEnvironmentNameAndTargetType(String environmentName, String targetType);

    List<Annotation> findAllByEnvironmentNameAndNamespace(String environmentName, String namespace);

    Optional<Annotation> findByIdAndEnvironmentName(Integer id, String environmentName);
}
