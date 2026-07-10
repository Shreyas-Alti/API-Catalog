package com.apicatalog.repository;

import com.apicatalog.model.ApiEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ApiEndpointRepo extends JpaRepository<ApiEndpoint, Long> {

    List<ApiEndpoint> findByRepositoryId(Long repositoryId);

    @Query("""
            SELECT e FROM ApiEndpoint e JOIN FETCH e.repository r WHERE
            (:repo      IS NULL OR LOWER(r.name)      LIKE LOWER(CONCAT('%', :repo,      '%'))) AND
            (:framework IS NULL OR LOWER(r.framework)  LIKE LOWER(CONCAT('%', :framework, '%'))) AND
            (:method    IS NULL OR UPPER(e.method)     = UPPER(:method))                        AND
            (:path      IS NULL OR LOWER(e.path)       LIKE LOWER(CONCAT('%', :path,      '%')))
            ORDER BY r.name, e.method, e.path
            """)
    List<ApiEndpoint> search(@Param("repo") String repo,
                             @Param("framework") String framework,
                             @Param("method") String method,
                             @Param("path") String path);
}
