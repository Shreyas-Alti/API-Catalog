package com.apicatalog.parser.koa;

import com.apicatalog.model.ExtractedApi;
import com.apicatalog.parser.ParserPlugin;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Parser for Koa (Node.js) projects using @koa/router or koa-router.
 * Matches: router.get('/path', ...), router.post('/path', ...), etc.
 */
@Component
public class KoaParser implements ParserPlugin {

    private static final Pattern ROUTE = Pattern.compile(
            "^\\s*[\\w$]+\\.(get|post|put|delete|patch)\\s*\\(\\s*['\"`]([^'\"`]+)['\"`]",
            Pattern.CASE_INSENSITIVE);

    @Override public String getFrameworkName() { return "Koa"; }

    @Override
    public boolean supports(Path root) {
        Path pkg = root.resolve("package.json");
        if (!Files.exists(pkg)) return false;
        try {
            String c = Files.readString(pkg);
            return (c.contains("\"koa\"") || c.contains("\"@koa/router\"") || c.contains("\"koa-router\""))
                    && !c.contains("\"express\"")
                    && !c.contains("\"fastify\"")
                    && !c.contains("\"@nestjs/core\"");
        } catch (IOException e) { return false; }
    }

    @Override
    public List<ExtractedApi> extract(Path root) {
        List<ExtractedApi> apis = new ArrayList<>();
        try {
            Files.walk(root)
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
