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

    Optional<Tag> findByNameAndPathAndEnvironmentName(String name, String path, String environmentName);

    @Query("SELECT DISTINCT t.name FROM Tag t WHERE t.type = :type AND t.environmentName = :environmentName")
    List<String> findTagNamesByType(@Param("type") String type,
                                    @Param("environmentName") String environmentName);

    List<Tag> findAllByNameAndTypeAndEnvironmentName(String name, String type, String environmentName);

    List<Tag> findAllByTypeAndEnvironmentName(String type, String environmentName);

    List<Tag> findAllByTypeAndPathStartsWithAndEnvironmentName(String type, String path, String environmentName);

    @Query("SELECT t.name AS name, t.type AS type, COUNT(t) AS uses, MAX(t.createdOn) AS lastUsed "
        + "FROM Tag t WHERE t.environmentName = :environmentName GROUP BY t.name, t.type")
    List<TagUsage> aggregateUsage(@Param("environmentName") String environmentName);

    void deleteAllByNameAndEnvironmentName(String name, String environmentName);

    /**
     * Detach one tag from exactly these paths.
     *
     * <p>Deliberately not {@link #deleteAllByName}, which would strip the tag
     * from the whole corpus. Bulk untag is the most destructive thing in the
     * tagging API and its scope is the set the user was shown — nothing else
     * carrying the same name may be touched (sqlx-agl).
     */
    void deleteAllByNameAndPathInAndEnvironmentName(String name, List<String> paths, String environmentName);

    interface TagUsage {
        String getName();
        String getType();
        long getUses();
        java.time.Instant getLastUsed();
    }
}
