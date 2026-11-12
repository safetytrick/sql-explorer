package com.github.safetytrick.sqlexplorer.auth;

import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

public interface UserRepository extends CrudRepository<User,Integer> {

    /** The legacy user: no issuer, looked up by its email, which is {@code admin}. */
    Optional<User> findFirstByIssuerIsNullAndEmailOrderByIdAsc(String email);

    Optional<User> findByIssuerAndSubject(String issuer, String subject);
}
