package com.github.safetytrick.sqlexplorer.auth;

import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

public interface UserRepository extends CrudRepository<User,Integer> {

    Optional<User> findFirstByIssuerIsNullAndEmailOrderByIdAsc(String email);

    Optional<User> findByIssuerAndSubject(String issuer, String subject);
}
