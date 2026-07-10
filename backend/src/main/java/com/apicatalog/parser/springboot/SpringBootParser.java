package com.apicatalog.parser.springboot;

import com.apicatalog.model.ExtractedApi;
import com.apicatalog.parser.ParserPlugin;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

/**
 * Parser for Spring Boot projects (Maven or Gradle).
 * Extracts endpoints defined with @GetMapping, @PostMapping, @PutMapping,
 * @DeleteMapping, @PatchMapping on classes annotated with @RestController or @Controller.
 */
@Component
public class SpringBootParser implements ParserPlugin {

    private static final Map<String, Pattern> METHOD_PATTERNS = Map.of(
            "GET",    Pattern.compile("@GetMapping(?:\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"\\s*\\))?"),
            "POST",   Pattern.compile("@PostMapping(?:\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"\\s*\\))?"),
            "PUT",    Pattern.compile("@PutMapping(?:\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"\\s*\\))?"),
            "DELETE", Pattern.compile("@DeleteMapping(?:\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"\\s*\\))?"),
            "PATCH",  Pattern.compile("@PatchMapping(?:\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"\\s*\\))?")
    );

    private static final Pattern CLASS_MAPPING  = Pattern.compile(
            "@RequestMapping\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"");
    private static final Pattern CLASS_NAME     = Pattern.compile(
            "(?:public|protected|private)?\\s+class\\s+(\\w+)");
    private static final Pattern METHOD_SIG     = Pattern.compile(
            "(?:public|private|protected)\\s+\\S+\\s+(\\w+)\\s*\\(");

    @Override
    public String getFrameworkName() {
        return "Spring Boot";
    }

    @Override
    public boolean supports(Path repositoryRoot) {
        for (String buildFile : List.of("pom.xml", "build.gradle", "build.gradle.kts")) {
            Path candidate = repositoryRoot.resolve(buildFile);
            if (Files.exists(candidate)) {
                try {
                    if (Files.readString(candidate).contains("spring-boot")) return true;
                } catch (IOException ignored) {}
            }
        }
        return false;
    }

    @Override
    public List<ExtractedApi> extract(Path repositoryRoot) {
        List<ExtractedApi> apis = new ArrayList<>();
        try {
            Files.walk(repositoryRoot)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains(java.io.File.separator + "test" + java.io.File.separator))
                    .forEach(javaFile -> {
                        try {
                            apis.addAll(parseFile(javaFile));
                        } catch (IOException ignored) {
                            // Degrade gracefully — skip unreadable files
                        }
                    });
        } catch (IOException ignored) {}
        return apis;
    }

    private List<ExtractedApi> parseFile(Path file) throws IOException {
        String content = Files.readString(file);

        if (!content.contains("@RestController") && !content.contains("@Controller")) {
            return Collections.emptyList();
        }

        List<ExtractedApi> apis = new ArrayList<>();
        String[] lines = content.split("\\r?\\n");

        String className = firstMatch(CLASS_NAME, content, 1);
        String basePath  = firstMatch(CLASS_MAPPING, content, 1);
        if (basePath == null) basePath = "";

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();

            String[] mapping = extractMapping(trimmed);
            if (mapping == null) continue;

            String httpMethod  = mapping[0];
            String methodPath  = mapping[1] != null ? mapping[1] : "";

            // Scan forward up to 8 lines for the method signature
            String handlerName = null;
            for (int j = i + 1; j < Math.min(i + 9, lines.length); j++) {
                handlerName = firstMatch(METHOD_SIG, lines[j].trim(), 1);
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

    /**
     * Returns [httpMethod, pathOrNull] if the line contains a mapping annotation, else null.
     */
    private String[] extractMapping(String line) {
        for (Map.Entry<String, Pattern> entry : METHOD_PATTERNS.entrySet()) {
            Matcher m = entry.getValue().matcher(line);
            if (m.find()) {
                return new String[]{ entry.getKey(), m.groupCount() >= 1 ? m.group(1) : null };
            }
        }
        return null;
    }

    private String joinPaths(String base, String path) {
        if (base == null) base = "";
        if (path == null) path = "";
        String joined = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                + (path.startsWith("/") ? path : (path.isEmpty() ? "" : "/" + path));
        return joined.isEmpty() ? "/" : joined;
    }

    private String firstMatch(Pattern pattern, String text, int group) {
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            try { return m.group(group); } catch (Exception ignored) {}
        }
        return null;
    }
}
