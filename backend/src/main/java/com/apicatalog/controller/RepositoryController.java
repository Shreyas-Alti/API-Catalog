package com.apicatalog.controller;

import com.apicatalog.dto.*;
import com.apicatalog.service.RepositoryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/repositories")
public class RepositoryController {

    private final RepositoryService repositoryService;

    public RepositoryController(RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
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
}
