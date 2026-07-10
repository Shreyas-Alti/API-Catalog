package com.apicatalog.parser;

import com.apicatalog.model.ExtractedApi;

import java.nio.file.Path;
import java.util.List;

/**
 * Contract that every framework-specific parser must implement.
 * The plugin receives the root path of a cloned repository and returns
 * a list of ExtractedApi objects conforming to the common extraction model.
 */
public interface ParserPlugin {

    /**
     * Returns the framework name this plugin handles (e.g. "Spring Boot").
     */
    String getFrameworkName();

    /**
     * Returns true if this plugin can handle the repository at the given path.
     * Detection should be based on repository contents (pom.xml, package.json, etc.).
     */
    boolean supports(Path repositoryRoot);

    /**
     * Extracts API metadata from the repository.
     * Missing information must be left null — never guessed.
     */
    List<ExtractedApi> extract(Path repositoryRoot);
}
