package com.apicatalog.service;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

@Service
public class CloneService {

    public record CloneResult(Path path, String commitSha) {}

    public CloneResult clone(String url) {
        try {
            Path tempDir = Files.createTempDirectory("api-catalog-");
            try (Git git = Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(tempDir.toFile())
                    .setDepth(1)
                    .call()) {

                // Disable background auto-GC so JGit doesn't try to access
                // lock files after we delete the temp directory in cleanup().
                git.getRepository().getConfig().setInt("gc", null, "auto", 0);
                git.getRepository().getConfig().save();

                String sha = "";
                try {
                    ObjectId head = git.getRepository().resolve("HEAD");
                    if (head != null) sha = head.getName();
                } catch (Exception ignored) {}

                return new CloneResult(tempDir, sha);
            }
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
