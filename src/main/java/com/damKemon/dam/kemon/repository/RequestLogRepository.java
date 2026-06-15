package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.RequestLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface RequestLogRepository extends MongoRepository<RequestLog, String> {

    List<RequestLog> findAllByOrderByTsDesc(Pageable pageable);
}
