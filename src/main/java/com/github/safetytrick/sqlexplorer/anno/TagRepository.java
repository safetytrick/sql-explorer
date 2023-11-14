package com.github.safetytrick.sqlexplorer.anno;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface TagRepository extends CrudRepository<Tag,Integer> {

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
}
