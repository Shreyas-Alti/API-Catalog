package com.apicatalog.parser.nestjs;

import com.apicatalog.model.ExtractedApi;
import com.apicatalog.parser.ParserPlugin;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

/**
 * Parser for NestJS (TypeScript) projects.
 * Extracts endpoints from @Controller classes using @Get, @Post, etc. decorators.
 */
@Component
public class NestJSParser implements ParserPlugin {

    private static final Map<String, Pattern> METHOD_PATTERNS = Map.of(
            "GET",    Pattern.compile("@Get(?:\\(\\s*(?:path\\s*=\\s*)?'([^']*)'\\s*\\))?"),
            "POST",   Pattern.compile("@Post(?:\\(\\s*(?:path\\s*=\\s*)?'([^']*)'\\s*\\))?"),
            "PUT",    Pattern.compile("@Put(?:\\(\\s*(?:path\\s*=\\s*)?'([^']*)'\\s*\\))?"),
            "DELETE", Pattern.compile("@Delete(?:\\(\\s*(?:path\\s*=\\s*)?'([^']*)'\\s*\\))?"),
            "PATCH",  Pattern.compile("@Patch(?:\\(\\s*(?:path\\s*=\\s*)?'([^']*)'\\s*\\))?")
    );

    private static final Pattern CONTROLLER = Pattern.compile(
            "@Controller\\(\\s*'([^']*)'\\s*\\)");
    private static final Pattern CLASS_NAME = Pattern.compile(
            "(?:export\\s+)?class\\s+(\\w+)");
    private static final Pattern METHOD_SIG  = Pattern.compile(
            "(?:async\\s+)?(\\w+)\\s*\\(");

    @Override
    public String getFrameworkName() { return "NestJS"; }

    @Override
    public boolean supports(Path repositoryRoot) {
        Path pkg = repositoryRoot.resolve("package.json");
        if (!Files.exists(pkg)) return false;
        try {
            return Files.readString(pkg).contains("\"@nestjs/core\"");
        } catch (IOException e) { return false; }
    }

    @Override
    public List<ExtractedApi> extract(Path repositoryRoot) {
        List<ExtractedApi> apis = new ArrayList<>();
        try {
            Files.walk(repositoryRoot)
                    .filter(p -> p.toString().endsWith(".ts"))
                    .filter(p -> !p.toString().contains("node_modules"))
                    .filter(p -> !p.toString().contains(".spec.") && !p.toString().contains(".test."))
                    .forEach(f -> {
                        try { apis.addAll(parseFile(f)); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
        return apis;
    }

    private List<ExtractedApi> parseFile(Path file) throws IOException {
        String content = Files.readString(file);
        if (!content.contains("@Controller")) return Collections.emptyList();

        List<ExtractedApi> apis = new ArrayList<>();
        String[] lines = content.split("\\r?\\n");

        String className = firstMatch(CLASS_NAME, content, 1);
        String basePath  = firstMatch(CONTROLLER, content, 1);
        if (basePath == null) basePath = "";

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            String[] mapping = extractMapping(line);
            if (mapping == null) continue;

            String httpMethod = mapping[0];
            String methodPath = mapping[1] != null ? mapping[1] : "";

            String handlerName = null;
            for (int j = i + 1; j < Math.min(i + 6, lines.length); j++) {
                String candidate = lines[j].trim();
                if (candidate.startsWith("@")) continue;
                handlerName = firstMatch(METHOD_SIG, candidate, 1);
                if (handlerName != null) break;
            }

            ExtractedApi api = new ExtractedApi();
            api.setMethod(httpMethod);
            api.setPath(joinPaths(basePath, methodPath));
            api.setController(className);
            api.setHandler(handlerName);
            apis.add(api);
        }
        return apis;
    }

    private String[] extractMapping(String line) {
        for (Map.Entry<String, Pattern> e : METHOD_PATTERNS.entrySet()) {
            Matcher m = e.getValue().matcher(line);
            if (m.find()) return new String[]{ e.getKey(), m.groupCount() >= 1 ? m.group(1) : null };
        }
        return null;
    }

    private String joinPaths(String base, String path) {
        if (base == null) base = "";
        if (path == null) path = "";
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String p = path.startsWith("/") ? path : (path.isEmpty() ? "" : "/" + path);
        String joined = b + p;
        return joined.isEmpty() ? "/" : (joined.startsWith("/") ? joined : "/" + joined);
    }

    private String firstMatch(Pattern pattern, String text, int group) {
        Matcher m = pattern.matcher(text);
        if (m.find()) { try { return m.group(group); } catch (Exception ignored) {} }
        return null;
    }
}
