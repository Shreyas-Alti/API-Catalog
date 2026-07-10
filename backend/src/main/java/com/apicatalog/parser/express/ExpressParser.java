package com.apicatalog.parser.express;

import com.apicatalog.model.ExtractedApi;
import com.apicatalog.parser.ParserPlugin;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

/**
 * Parser for Express.js projects.
 * Matches: app.get('/path', handler), router.post('/path', handler), etc.
 */
@Component
public class ExpressParser implements ParserPlugin {

    // Matches: identifier.verb('path', ...) – the identifier can be any variable name
    private static final Pattern ROUTE = Pattern.compile(
            "^\\s*[\\w$]+\\.(get|post|put|delete|patch)\\s*\\(\\s*['\"`]([^'\"`]+)['\"`]",
            Pattern.CASE_INSENSITIVE);

    // Tries to extract a named callback: ..., handlerName) or ..., handlerName,
    private static final Pattern HANDLER = Pattern.compile(",\\s*([A-Za-z_$][\\w$]*)\\s*[,)]");

    @Override
    public String getFrameworkName() { return "Express"; }

    @Override
    public boolean supports(Path repositoryRoot) {
        Path pkg = repositoryRoot.resolve("package.json");
        if (!Files.exists(pkg)) return false;
        try {
            String content = Files.readString(pkg);
            return content.contains("\"express\"")
                    && !content.contains("\"@nestjs/core\"")
                    && !content.contains("\"fastify\"");
        } catch (IOException e) { return false; }
    }

    @Override
    public List<ExtractedApi> extract(Path repositoryRoot) {
        List<ExtractedApi> apis = new ArrayList<>();
        try {
            Files.walk(repositoryRoot)
                    .filter(p -> {
                        String s = p.toString();
                        return (s.endsWith(".js") || s.endsWith(".ts") || s.endsWith(".mjs"))
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

            String httpMethod = m.group(1).toUpperCase();
            String path       = m.group(2);

            // Try to find a named handler (last argument before closing paren)
            String handler = null;
            String after = line.substring(m.end());
            Matcher hm = HANDLER.matcher(after);
            String lastCandidate = null;
            while (hm.find()) lastCandidate = hm.group(1);
            if (lastCandidate != null && !isKeyword(lastCandidate)) handler = lastCandidate;

            ExtractedApi api = new ExtractedApi();
            api.setMethod(httpMethod);
            api.setPath(path);
            api.setHandler(handler);
            apis.add(api);
        }
        return apis;
    }

    private boolean isKeyword(String s) {
        return Set.of("req", "res", "next", "err", "null", "true", "false",
                "async", "function", "return", "const", "let", "var").contains(s);
    }
}
