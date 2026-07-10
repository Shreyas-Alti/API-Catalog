package com.apicatalog.parser.fastapi;

import com.apicatalog.model.ExtractedApi;
import com.apicatalog.parser.ParserPlugin;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

/**
 * Parser for FastAPI (Python) projects.
 * Matches: @app.get("/path"), @router.post("/path"), etc.
 */
@Component
public class FastAPIParser implements ParserPlugin {

    // @app.get("/path") or @router.post("/path", ...)
    private static final Pattern DECORATOR = Pattern.compile(
            "^@[\\w.]+\\.(get|post|put|delete|patch)\\s*\\(\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);

    // async def handler_name( or def handler_name(
    private static final Pattern FUNC_DEF = Pattern.compile(
            "^(?:async\\s+)?def\\s+(\\w+)\\s*\\(");

    @Override
    public String getFrameworkName() { return "FastAPI"; }

    @Override
    public boolean supports(Path repositoryRoot) {
        return containsDependency(repositoryRoot, "fastapi");
    }

    @Override
    public List<ExtractedApi> extract(Path repositoryRoot) {
        List<ExtractedApi> apis = new ArrayList<>();
        try {
            Files.walk(repositoryRoot)
                    .filter(p -> p.toString().endsWith(".py"))
                    .filter(p -> !p.toString().contains("test_") && !p.getFileName().toString().startsWith("test_"))
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
            Matcher m = DECORATOR.matcher(lines[i].trim());
            if (!m.find()) continue;

            String httpMethod = m.group(1).toUpperCase();
            String path = m.group(2);

            // Find the function definition in the next few lines (skip other decorators)
            String handlerName = null;
            for (int j = i + 1; j < Math.min(i + 6, lines.length); j++) {
                String candidate = lines[j].trim();
                if (candidate.startsWith("@")) continue;
                Matcher fm = FUNC_DEF.matcher(candidate);
                if (fm.find()) { handlerName = fm.group(1); break; }
            }

            ExtractedApi api = new ExtractedApi();
            api.setMethod(httpMethod);
            api.setPath(path);
            api.setHandler(handlerName);
            apis.add(api);
        }
        return apis;
    }

    private boolean containsDependency(Path repositoryRoot, String dep) {
        for (String file : List.of("requirements.txt", "pyproject.toml", "Pipfile", "setup.py")) {
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
