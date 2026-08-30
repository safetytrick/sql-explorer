package com.github.safetytrick.sqlexplorer.commentary;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

interface TagRepository extends CrudRepository<Tag,Integer> {

    @Query("SELECT DISTINCT t.name FROM Tag t WHERE t.name LIKE :startsWith%")
    List<String> findTagNamesStartingWith(@Param("startsWith") String startsWith);

    List<Tag> findAllByPath(String path);
    List<Tag> findAllByPathIn(List<String> paths);

    default Map<String, List<Tag>> findAllByPathInGroupedByPath(List<String> paths) {
        return findAllByPathIn(paths).stream().collect(Collectors.groupingBy(Tag::getPath));
    }

    Optional<Tag> findByNameAndPath(String name, String path);

    @Query("SELECT DISTINCT t.name FROM Tag t WHERE t.type = :type")
    List<String> findTagNamesByType(@Param("type") String type);

    List<Tag> findAllByNameAndType(String name, String type);

    List<Tag> findAllByType(String type);

    List<Tag> findAllByTypeAndPathStartsWith(String type, String path);

    @Query("SELECT t.name AS name, t.type AS type, COUNT(t) AS uses, MAX(t.createdOn) AS lastUsed "
        + "FROM Tag t GROUP BY t.name, t.type")
    List<TagUsage> aggregateUsage();

    void deleteAllByName(String name);

    /**
     * Detach one tag from exactly these paths.
     *
     * <p>Deliberately not {@link #deleteAllByName}, which would strip the tag
     * from the whole corpus. Bulk untag is the most destructive thing in the
     * tagging API and its scope is the set the user was shown — nothing else
     * carrying the same name may be touched (sqlx-agl).
     */
    void deleteAllByNameAndPathIn(String name, List<String> paths);

    interface TagUsage {
        String getName();
        String getType();
        long getUses();
        java.time.Instant getLastUsed();
    }
}
