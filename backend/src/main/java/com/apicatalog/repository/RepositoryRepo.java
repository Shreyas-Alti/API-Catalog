package com.apicatalog.repository;

import com.apicatalog.model.Repository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface RepositoryRepo extends JpaRepository<Repository, Long> {

    @EntityGraph(attributePaths = "endpoints")
    @Query("SELECT r FROM Repository r")
    List<Repository> findAllWithEndpoints();

    @EntityGraph(attributePaths = "endpoints")
    @Query("SELECT r FROM Repository r WHERE r.id = :id")
    Optional<Repository> findByIdWithEndpoints(Long id);
}
