package com.github.safetytrick.sqlexplorer.commentary;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

/**
 * Annotation rows, always asked for within one environment.
 *
 * <p>Every method takes the environment as a parameter, for the reason
 * {@link CommentaryEnvironment} gives: forgetting to scope is then a compile
 * failure rather than a silent read of another corpus.
 */
interface AnnotationRepository extends CrudRepository<Annotation, Integer> {

    /** Every row of a kind whose schema is one of these; the exact target is matched in memory. */
    List<Annotation> findAllByEnvironmentNameAndTargetTypeAndSchemaNameIn(
        String environmentName, String targetType, List<String> schemaNames);

    /** Query annotations for a set of digests. */
    List<Annotation> findAllByEnvironmentNameAndTargetTypeAndQueryDigestIn(
        String environmentName, String targetType, List<String> digests);

    /** Every row of a kind — the environment kind has no parts to narrow by. */
    List<Annotation> findAllByEnvironmentNameAndTargetType(String environmentName, String targetType);

    List<Annotation> findAllByEnvironmentNameAndNamespace(String environmentName, String namespace);

    Optional<Annotation> findByIdAndEnvironmentName(Integer id, String environmentName);
}
