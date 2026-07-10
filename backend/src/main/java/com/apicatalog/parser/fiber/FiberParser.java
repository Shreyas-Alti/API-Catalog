package com.apicatalog.parser.fiber;

import com.apicatalog.model.ExtractedApi;
import com.apicatalog.parser.ParserPlugin;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

/**
 * Parser for Fiber (Go) projects.
 * Matches: app.Get("/path", handler), api.Post("/path", handler), etc.
 */
@Component
public class FiberParser implements ParserPlugin {

    // app.Get("/path", handlerFunc) – title-cased HTTP methods
    private static final Pattern ROUTE = Pattern.compile(
            "^\\s*[\\w]+\\.(Get|Post|Put|Delete|Patch|All)\\s*\\(\\s*\"([^\"]+)\"\\s*,\\s*(\\w+)?");

    @Override
    public String getFrameworkName() { return "Fiber"; }

    @Override
    public boolean supports(Path repositoryRoot) {
        Path goMod = repositoryRoot.resolve("go.mod");
        if (!Files.exists(goMod)) return false;
        try {
            return Files.readString(goMod).contains("gofiber/fiber");
        } catch (IOException e) { return false; }
    }

    @Override
    public List<ExtractedApi> extract(Path repositoryRoot) {
        List<ExtractedApi> apis = new ArrayList<>();
        try {
            Files.walk(repositoryRoot)
                    .filter(p -> p.toString().endsWith(".go"))
                    .filter(p -> !p.toString().contains("vendor"))
                    .filter(p -> !p.getFileName().toString().endsWith("_test.go"))
                    .forEach(f -> {
                        try { apis.addAll(parseFile(f)); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
        return apis;
    }

    private List<ExtractedApi> parseFile(Path file) throws IOException {
        List<ExtractedApi> apis = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            Matcher m = ROUTE.matcher(line);
            if (!m.find()) continue;
            String verb = m.group(1);
            if (verb.equals("All")) verb = "GET"; // Map .All() to GET as best-effort
            ExtractedApi api = new ExtractedApi();
            api.setMethod(verb.toUpperCase());
            api.setPath(m.group(2));
            if (m.groupCount() >= 3) api.setHandler(m.group(3));
            apis.add(api);
        }
        return apis;
    }
}
