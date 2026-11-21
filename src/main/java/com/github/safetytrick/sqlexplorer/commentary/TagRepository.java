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

    Optional<Tag> findByNameAndEnvironmentNameAndTypeAndSchemaNameAndTableNameAndColumnNameAndQueryDigest(
        String name, String environmentName, String type,
        String schemaName, String tableName, String columnName, String queryDigest);

    @Query("SELECT DISTINCT t.name FROM Tag t WHERE t.type = :type AND t.environmentName = :environmentName")
    List<String> findTagNamesByType(@Param("type") String type,
                                    @Param("environmentName") String environmentName);

    List<Tag> findAllByNameAndTypeAndEnvironmentName(String name, String type, String environmentName);

    List<Tag> findAllByTypeAndEnvironmentName(String type, String environmentName);

    List<Tag> findAllByTypeAndSchemaNameInAndEnvironmentName(String type, List<String> schemaNames,
                                                             String environmentName);

    List<Tag> findAllByTypeAndQueryDigestInAndEnvironmentName(String type, List<String> digests,
                                                              String environmentName);

    List<Tag> findAllByTypeAndSchemaNameAndEnvironmentName(String type, String schemaName,
                                                           String environmentName);

    @Query("SELECT t.name AS name, t.type AS type, COUNT(t) AS uses, MAX(t.createdOn) AS lastUsed "
        + "FROM Tag t WHERE t.environmentName = :environmentName GROUP BY t.name, t.type")
    List<TagUsage> aggregateUsage(@Param("environmentName") String environmentName);

    void deleteAllByNameAndEnvironmentName(String name, String environmentName);

    long deleteByIdAndEnvironmentName(Integer id, String environmentName);

    void deleteAllByIdInAndEnvironmentName(List<Integer> ids, String environmentName);

    interface TagUsage {
        String getName();
        String getType();
        long getUses();
        java.time.Instant getLastUsed();
    }
}
