package com.github.safetytrick.sqlexplorer.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AccessTokenRepository extends CrudRepository<AccessToken, Integer> {

    Optional<AccessToken> findByTokenHash(String tokenHash);

    List<AccessToken> findByUserIdOrderByCreatedOnDescIdDesc(Integer userId);

    List<AccessToken> findAllByOrderByCreatedOnDescIdDesc();

    @Modifying
    @Transactional
    @Query("update AccessToken t set t.lastUsedOn = :now where t.id = :id and (t.lastUsedOn is null or t.lastUsedOn < :cutoff)")
    int touch(@Param("id") Integer id, @Param("now") Instant now, @Param("cutoff") Instant cutoff);
}
