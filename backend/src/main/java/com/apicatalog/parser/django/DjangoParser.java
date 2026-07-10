package com.apicatalog.parser.django;

import com.apicatalog.model.ExtractedApi;
import com.apicatalog.parser.ParserPlugin;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

/**
 * Parser for Django REST Framework projects.
 * Sources:
 *   - urls.py: path('endpoint/', view, name='...')
 *   - viewsets: @action(detail=False, methods=['get', 'post'])
 */
@Component
public class DjangoParser implements ParserPlugin {

    // path('endpoint/', SomeView.as_view(), ...) or path('endpoint/', some_func, ...)
    private static final Pattern URL_PATH = Pattern.compile(
            "path\\s*\\(\\s*[\"']([^\"']*)[\"']\\s*,\\s*([\\w.]+)");

    // re_path(r'...', view, ...)
    private static final Pattern RE_PATH = Pattern.compile(
            "re_path\\s*\\(\\s*r?[\"']([^\"']*)[\"']\\s*,\\s*([\\w.]+)");

    // router.register(r'basename', ViewSet, basename='...')
    private static final Pattern ROUTER_REGISTER = Pattern.compile(
            "register\\s*\\(\\s*r?[\"']([^\"']+)[\"']\\s*,\\s*(\\w+)");

    // @action(detail=False, methods=['get','post'])
    private static final Pattern ACTION = Pattern.compile(
            "@action\\s*\\([^)]*methods\\s*=\\s*\\[([^\\]]+)\\](?:[^)]*detail\\s*=\\s*(True|False))?");

    private static final Pattern FUNC_DEF = Pattern.compile(
            "def\\s+(\\w+)\\s*\\(");

    @Override
    public String getFrameworkName() { return "Django REST"; }

    @Override
    public boolean supports(Path repositoryRoot) {
        for (String file : List.of("requirements.txt", "pyproject.toml", "Pipfile")) {
            Path candidate = repositoryRoot.resolve(file);
            if (Files.exists(candidate)) {
                try {
                    String c = Files.readString(candidate).toLowerCase();
                    if (c.contains("django") && !c.contains("fastapi") && !c.contains("flask")) return true;
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
                    .filter(p -> p.toString().endsWith(".py"))
                    .filter(p -> !p.getFileName().toString().startsWith("test_"))
                    .forEach(f -> {
                        try {
                            String name = f.getFileName().toString();
                            if (name.equals("urls.py")) apis.addAll(parseUrlsFile(f));
                            else apis.addAll(parseViewFile(f));
                        } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
        return apis;
    }

    private List<ExtractedApi> parseUrlsFile(Path file) throws IOException {
        List<ExtractedApi> apis = new ArrayList<>();
        String content = Files.readString(file);

        for (Pattern p : List.of(URL_PATH, RE_PATH)) {
            Matcher m = p.matcher(content);
            while (m.find()) {
                String path = "/" + m.group(1).replaceAll("<[^>]+>", "{param}");
                String viewRef = m.group(2);
                String inferredMethod = inferMethodFromView(viewRef);
                ExtractedApi api = new ExtractedApi();
                api.setMethod(inferredMethod);
                api.setPath(path);
                api.setController(viewRef);
                apis.add(api);
            }
        }

        Matcher rm = ROUTER_REGISTER.matcher(content);
        while (rm.find()) {
            String basePath = "/" + rm.group(1);
            String viewSet  = rm.group(2);
            // ViewSets expose list (GET), create (POST), retrieve (GET), update (PUT), destroy (DELETE)
            for (String[] pair : new String[][]{ {"GET", basePath}, {"POST", basePath},
                    {"GET", basePath + "/{id}"}, {"PUT", basePath + "/{id}"}, {"DELETE", basePath + "/{id}"} }) {
                ExtractedApi api = new ExtractedApi();
                api.setMethod(pair[0]);
                api.setPath(pair[1]);
                api.setController(viewSet);
                apis.add(api);
            }
        }
        return apis;
    }

    private List<ExtractedApi> parseViewFile(Path file) throws IOException {
        List<ExtractedApi> apis = new ArrayList<>();
        String[] lines = Files.readString(file).split("\\r?\\n");

        for (int i = 0; i < lines.length; i++) {
            Matcher am = ACTION.matcher(lines[i].trim());
            if (!am.find()) continue;

            String methodsStr = am.group(1);
            String handlerName = null;
            for (int j = i + 1; j < Math.min(i + 4, lines.length); j++) {
                Matcher fm = FUNC_DEF.matcher(lines[j].trim());
                if (fm.find()) { handlerName = fm.group(1); break; }
            }

            String path = "/" + (handlerName != null ? handlerName.replace("_", "-") : "action");
            for (String method : parseMethods(methodsStr)) {
                ExtractedApi api = new ExtractedApi();
                api.setMethod(method);
                api.setPath(path);
                api.setHandler(handlerName);
                apis.add(api);
            }
        }
        return apis;
    }

    private String inferMethodFromView(String viewRef) {
        String lower = viewRef.toLowerCase();
        if (lower.contains("list") || lower.contains("retrieve")) return "GET";
        if (lower.contains("create")) return "POST";
        if (lower.contains("update")) return "PUT";
        if (lower.contains("destroy") || lower.contains("delete")) return "DELETE";
        return "GET";
    }

    private List<String> parseMethods(String methodsStr) {
        List<String> result = new ArrayList<>();
        for (String m : methodsStr.split(",")) {
            String clean = m.trim().replace("'", "").replace("\"", "").toUpperCase();
            if (!clean.isEmpty()) result.add(clean);
        }
        return result.isEmpty() ? List.of("GET") : result;
    }
}
