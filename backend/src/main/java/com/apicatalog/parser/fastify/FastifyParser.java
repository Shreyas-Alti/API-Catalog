package com.apicatalog.parser.fastify;

import com.apicatalog.model.ExtractedApi;
import com.apicatalog.parser.ParserPlugin;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

/**
 * Parser for Fastify projects.
 * Matches: fastify.get('/path', ...), server.post('/path', ...), etc.
 */
@Component
public class FastifyParser implements ParserPlugin {

    private static final Pattern ROUTE = Pattern.compile(
            "^\\s*[\\w$]+\\.(get|post|put|delete|patch)\\s*\\(\\s*['\"`]([^'\"`]+)['\"`]",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String getFrameworkName() { return "Fastify"; }

    @Override
    public boolean supports(Path repositoryRoot) {
        Path pkg = repositoryRoot.resolve("package.json");
        if (!Files.exists(pkg)) return false;
        try {
            return Files.readString(pkg).contains("\"fastify\"");
        } catch (IOException e) { return false; }
    }

    @Override
    public List<ExtractedApi> extract(Path repositoryRoot) {
        List<ExtractedApi> apis = new ArrayList<>();
        try {
            Files.walk(repositoryRoot)
                    .filter(p -> {
                        String s = p.toString();
                        return (s.endsWith(".js") || s.endsWith(".ts"))
                                && !s.contains("node_modules")
                                && !s.contains(".test.") && !s.contains(".spec.");
                    })
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
            ExtractedApi api = new ExtractedApi();
            api.setMethod(m.group(1).toUpperCase());
            api.setPath(m.group(2));
            apis.add(api);
        }
        return apis;
    }
}
