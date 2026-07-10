package com.apicatalog.service;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

@Service
public class CloneService {

    public Path clone(String url) {
        try {
            Path tempDir = Files.createTempDirectory("api-catalog-");
            Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(tempDir.toFile())
                    .setDepth(1)
                    .call()
                    .close();
            return tempDir;
        } catch (GitAPIException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Failed to clone repository: " + e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to create temporary directory");
        }
    }

    public void cleanup(Path path) {
        if (path == null || !Files.exists(path)) return;
        try {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }
}
