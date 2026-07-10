package com.apicatalog.service;

import com.apicatalog.model.ExtractedApi;
import com.apicatalog.parser.ParserPlugin;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Delegates framework detection and API extraction to the correct ParserPlugin.
 * All registered plugins are injected by Spring; the first one whose supports()
 * returns true for a given repository is used.
 */
@Service
public class ExtractionService {

    private final List<ParserPlugin> parsers;

    public ExtractionService(List<ParserPlugin> parsers) {
        this.parsers = parsers;
    }

    public String detectFramework(Path repositoryRoot) {
        for (ParserPlugin parser : parsers) {
            if (parser.supports(repositoryRoot)) {
                return parser.getFrameworkName();
            }
        }
        return "Unsupported";
    }

    public List<ExtractedApi> extract(Path repositoryRoot) {
        for (ParserPlugin parser : parsers) {
            if (parser.supports(repositoryRoot)) {
                return parser.extract(repositoryRoot);
            }
        }
        return Collections.emptyList();
    }
}
