package com.github.safetytrick.sqlexplorer.commentary;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

interface TagRepository extends CrudRepository<Tag,Integer> {

    @Query("SELECT DISTINCT t.name FROM Tag t "
        + "WHERE t.name LIKE :startsWith% AND t.environmentName = :environmentName")
    List<String> findTagNamesStartingWith(@Param("startsWith") String startsWith,
                                          @Param("environmentName") String environmentName);

    List<Tag> findAllByPathAndEnvironmentName(String path, String environmentName);
    List<Tag> findAllByPathInAndEnvironmentName(List<String> paths, String environmentName);

    default Map<String, List<Tag>> findAllByPathInGroupedByPath(List<String> paths, String environmentName) {
        return findAllByPathInAndEnvironmentName(paths, environmentName).stream()
            .collect(Collectors.groupingBy(Tag::getPath));
    }

    /**
     * One association, addressed by its typed target rather than its path.
     *
     * <p>The typed columns are the unique key, so this is the exact lookup the
     * key enforces — a path-based one asked a differently-shaped question than
     * the constraint answered.
     */
    Optional<Tag> findByNameAndEnvironmentNameAndTypeAndSchemaNameAndTableNameAndQueryDigest(
        String name, String environmentName, String type,
        String schemaName, String tableName, String queryDigest);

    @Query("SELECT DISTINCT t.name FROM Tag t WHERE t.type = :type AND t.environmentName = :environmentName")
    List<String> findTagNamesByType(@Param("type") String type,
                                    @Param("environmentName") String environmentName);

    List<Tag> findAllByNameAndTypeAndEnvironmentName(String name, String type, String environmentName);

    List<Tag> findAllByTypeAndEnvironmentName(String type, String environmentName);

    /**
     * Every tag of a kind whose schema is one of these.
     *
     * <p>Deliberately keyed on schema alone rather than on (schema, table)
     * pairs: a page's rows sit in a handful of schemas, so this over-fetches by
     * a little and the exact pair is matched in memory. A tuple IN would be the
     * precise query and is not portable; building one predicate per pair would
     * be hundreds of them (sqlx-3ai).
     */
    List<Tag> findAllByTypeAndSchemaNameInAndEnvironmentName(String type, List<String> schemaNames,
                                                             String environmentName);

    /** Query tags for a set of digests. */
    List<Tag> findAllByTypeAndQueryDigestInAndEnvironmentName(String type, List<String> digests,
                                                              String environmentName);

    /** Every tag of a kind within one schema — replaces a path-prefix LIKE. */
    List<Tag> findAllByTypeAndSchemaNameAndEnvironmentName(String type, String schemaName,
                                                           String environmentName);

    @Query("SELECT t.name AS name, t.type AS type, COUNT(t) AS uses, MAX(t.createdOn) AS lastUsed "
        + "FROM Tag t WHERE t.environmentName = :environmentName GROUP BY t.name, t.type")
    List<TagUsage> aggregateUsage(@Param("environmentName") String environmentName);

    void deleteAllByNameAndEnvironmentName(String name, String environmentName);

    /**
     * Detach one association, but only from the environment asking.
     *
     * <p>Returns how many rows went, so the caller can tell "deleted" from "not
     * yours" — {@code deleteById} cannot, and reported both as success.
     */
    long deleteByIdAndEnvironmentName(Integer id, String environmentName);

    /**
     * Detach one tag from exactly these paths.
     *
     * <p>Deliberately not {@link #deleteAllByName}, which would strip the tag
     * from the whole corpus. Bulk untag is the most destructive thing in the
     * tagging API and its scope is the set the user was shown — nothing else
     * carrying the same name may be touched (sqlx-agl).
     */
    /**
     * Detach exactly these associations.
     *
     * <p>By id, resolved from the targets the caller asked about — so nothing
     * carrying the same name outside that set can be touched, which is the
     * property the path-scoped delete guaranteed before targets replaced paths
     * (sqlx-agl, sqlx-3ai).
     */
    void deleteAllByIdInAndEnvironmentName(List<Integer> ids, String environmentName);

    interface TagUsage {
        String getName();
        String getType();
        long getUses();
        java.time.Instant getLastUsed();
    }
}
