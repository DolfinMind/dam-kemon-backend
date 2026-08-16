package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.User;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends MongoRepository<User, String> {
    Optional<User> findByEmail(String email);
    Optional<User> findByUsername(String username);

    /** Duplicate-tolerant lookup. {@link #findByUsername} throws
     *  IncorrectResultSizeDataAccessException if a re-import left two rows with the
     *  same username (the unique index can't build over pre-existing dupes) — which
     *  500s login. This never enforces cardinality, so callers dedupe/pick themselves. */
    List<User> findAllByUsername(String username);

    /** Duplicate-tolerant email lookup, same reason as {@link #findAllByUsername}. */
    List<User> findAllByEmail(String email);

    Optional<User> findByVerifyToken(String token);

    Optional<User> findByResetToken(String token);
}
