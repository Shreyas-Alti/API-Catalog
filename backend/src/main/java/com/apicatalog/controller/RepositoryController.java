package com.apicatalog.controller;

import com.apicatalog.dto.*;
import com.apicatalog.service.OpenApiGeneratorService;
import com.apicatalog.service.RepositoryService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/repositories")
public class RepositoryController {

    private final RepositoryService        repositoryService;
    private final OpenApiGeneratorService  openApiService;

    public RepositoryController(RepositoryService repositoryService,
                                OpenApiGeneratorService openApiService) {
        this.repositoryService = repositoryService;
        this.openApiService    = openApiService;
    }

    // Phase 2/3/4 – submit URL, clone, detect, extract
    @PostMapping("/submit")
    public ResponseEntity<SubmitResponse> submit(@Valid @RequestBody SubmitRequest request) {
        return ResponseEntity.ok(repositoryService.submit(request));
    }

    // Phase 6 – save reviewed APIs to DB
    @PostMapping("/save")
    public ResponseEntity<RepositoryDetailDto> save(@Valid @RequestBody SaveRequest request) {
        return ResponseEntity.ok(repositoryService.save(request));
    }

    // Phase 7 – list all saved repositories
    @GetMapping
    public ResponseEntity<List<RepositorySummaryDto>> listAll() {
        return ResponseEntity.ok(repositoryService.listAll());
    }

    // Phase 7 – get a single repository with its endpoints
    @GetMapping("/{id}")
    public ResponseEntity<RepositoryDetailDto> getById(@PathVariable Long id) {
        return ResponseEntity.ok(repositoryService.getById(id));
    }

    // Feature 3 – delete a repository and all its endpoints
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        repositoryService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // Feature 4 – re-clone and re-extract a saved repository
    @PostMapping("/{id}/rescan")
    public ResponseEntity<RepositoryDetailDto> rescan(@PathVariable Long id) {
        return ResponseEntity.ok(repositoryService.rescan(id));
    }

    // OpenAPI 3.1 document (generated + cached)
    @GetMapping(value = "/{id}/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> openapiJson(@PathVariable Long id) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(openApiService.getOrGenerate(id));
    }
}
