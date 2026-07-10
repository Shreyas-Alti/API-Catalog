package com.apicatalog.parser.flask;

import com.apicatalog.model.ExtractedApi;
import com.apicatalog.parser.ParserPlugin;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

/**
 * Parser for Flask (Python) projects.
 * Handles:
 *   @app.route('/path', methods=['GET','POST'])
 *   @bp.route('/path')
 *   @app.get('/path')  (Flask 2+)
 */
@Component
public class FlaskParser implements ParserPlugin {

    // @app.route('/path') or @bp.route('/path', methods=['GET', 'POST'])
    private static final Pattern ROUTE = Pattern.compile(
            "^@[\\w.]+\\.route\\s*\\(\\s*[\"']([^\"']+)[\"'](?:.*?methods\\s*=\\s*\\[([^\\]]+)\\])?",
            Pattern.CASE_INSENSITIVE);

    // @app.get('/path') – Flask 2+ shorthand
    private static final Pattern SHORTHAND = Pattern.compile(
            "^@[\\w.]+\\.(get|post|put|delete|patch)\\s*\\(\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FUNC_DEF = Pattern.compile(
            "^(?:async\\s+)?def\\s+(\\w+)\\s*\\(");

    @Override
    public String getFrameworkName() { return "Flask"; }

    @Override
    public boolean supports(Path repositoryRoot) {
        return containsDependency(repositoryRoot, "flask")
                && !containsDependency(repositoryRoot, "fastapi");
    }

    @Override
    public List<ExtractedApi> extract(Path repositoryRoot) {
        List<ExtractedApi> apis = new ArrayList<>();
        try {
            Files.walk(repositoryRoot)
                    .filter(p -> p.toString().endsWith(".py"))
                    .filter(p -> !p.getFileName().toString().startsWith("test_"))
                    .forEach(f -> {
                        try { apis.addAll(parseFile(f)); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
        return apis;
    }

    private List<ExtractedApi> parseFile(Path file) throws IOException {
        List<ExtractedApi> apis = new ArrayList<>();
        String[] lines = Files.readString(file).split("\\r?\\n");

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            List<String> methods = null;
            String path = null;

            Matcher rm = ROUTE.matcher(trimmed);
            if (rm.find()) {
                path = rm.group(1);
                String methodsStr = rm.group(2);
                methods = parseMethods(methodsStr);
            } else {
                Matcher sm = SHORTHAND.matcher(trimmed);
                if (sm.find()) {
                    methods = List.of(sm.group(1).toUpperCase());
                    path = sm.group(2);
                }
            }

            if (path == null) continue;

            String handlerName = null;
            for (int j = i + 1; j < Math.min(i + 4, lines.length); j++) {
                Matcher fm = FUNC_DEF.matcher(lines[j].trim());
                if (fm.find()) { handlerName = fm.group(1); break; }
            }

            for (String method : methods) {
                ExtractedApi api = new ExtractedApi();
                api.setMethod(method);
                api.setPath(path);
                api.setHandler(handlerName);
                apis.add(api);
            }
        }
        return apis;
    }

    private List<String> parseMethods(String methodsStr) {
        if (methodsStr == null || methodsStr.isBlank()) return List.of("GET");
        List<String> result = new ArrayList<>();
        for (String m : methodsStr.split(",")) {
            String clean = m.trim().replace("'", "").replace("\"", "").toUpperCase();
            if (!clean.isEmpty() && !clean.equals("HEAD") && !clean.equals("OPTIONS")) {
                result.add(clean);
            }
        }
        return result.isEmpty() ? List.of("GET") : result;
    }

    private boolean containsDependency(Path repositoryRoot, String dep) {
        for (String file : List.of("requirements.txt", "pyproject.toml", "Pipfile")) {
            Path candidate = repositoryRoot.resolve(file);
            if (Files.exists(candidate)) {
                try {
                    if (Files.readString(candidate).toLowerCase().contains(dep)) return true;
                } catch (IOException ignored) {}
            }
        }
        return false;
    }
}
