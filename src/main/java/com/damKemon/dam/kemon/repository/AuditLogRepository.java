package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.AuditLogEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface AuditLogRepository extends MongoRepository<AuditLogEntry, String> {
    List<AuditLogEntry> findAllByOrderByTsDesc(Pageable pageable);
}
