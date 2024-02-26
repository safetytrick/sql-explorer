package com.github.safetytrick.sqlexplorer.evidence.source;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface SqlSourceEntityRepository extends CrudRepository<SqlSourceEntity, Integer> {

    @Query("SELECT ss FROM SqlSourceEntity ss WHERE ss.createdOn = " +
            "(SELECT max(child.createdOn) FROM SqlSourceEntity child WHERE child.sourceRef = :sourceRef) " +
            "AND ss.sourceRef = :sourceRef")
    Optional<SqlSourceEntity> findLatestBySourceRef(@Param("sourceRef")String sourceRef);

    Optional<SqlSourceEntity> findByMd5Hash(String md5);

    List<SqlSourceEntity> findAllBySourceUrl(String sourceUrl);

    List<SourceRefProjection> findDistinctBy();

}
