package com.apicatalog.parser.echo;

import com.apicatalog.model.ExtractedApi;
import com.apicatalog.parser.ParserPlugin;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Parser for Echo (Go) projects.
 * Matches: e.GET("/path", handler), g.POST("/path", handler), etc.
 */
@Component
public class EchoParser implements ParserPlugin {

    // Echo uses uppercase HTTP method names like Gin
    private static final Pattern ROUTE = Pattern.compile(
            "^\\s*[\\w]+\\.(GET|POST|PUT|DELETE|PATCH)\\s*\\(\\s*\"([^\"]+)\"\\s*,\\s*(\\w+)?");

    @Override public String getFrameworkName() { return "Echo"; }

    @Override
    public boolean supports(Path root) {
        Path goMod = root.resolve("go.mod");
        if (!Files.exists(goMod)) return false;
        try {
            return Files.readString(goMod).contains("labstack/echo");
        } catch (IOException e) { return false; }
    }

    @Override
    public List<ExtractedApi> extract(Path root) {
        List<ExtractedApi> apis = new ArrayList<>();
        try {
            Files.walk(root)
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
            ExtractedApi api = new ExtractedApi();
            api.setMethod(m.group(1));
            api.setPath(m.group(2));
            if (m.groupCount() >= 3) api.setHandler(m.group(3));
            apis.add(api);
        }
        return apis;
    }
}
