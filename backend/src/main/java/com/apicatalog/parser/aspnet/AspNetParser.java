package com.apicatalog.parser.aspnet;

import com.apicatalog.model.ExtractedApi;
import com.apicatalog.parser.ParserPlugin;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

/**
 * Parser for ASP.NET Core Web API projects.
 * Handles [HttpGet], [HttpPost("{id}")], [Route("prefix")] on controllers.
 */
@Component
public class AspNetParser implements ParserPlugin {

    private static final Pattern HTTP_METHOD = Pattern.compile(
            "\\[Http(Get|Post|Put|Delete|Patch)(?:\\s*\\(\\s*\"([^\"]*)\"\\s*\\))?\\]");
    private static final Pattern ROUTE_ATTR  = Pattern.compile(
            "\\[Route\\s*\\(\\s*\"([^\"]*)\"\\s*\\)\\]");
    private static final Pattern CLASS_NAME  = Pattern.compile(
            "(?:public|internal)\\s+class\\s+(\\w+)(?:Controller)?");
    private static final Pattern METHOD_SIG  = Pattern.compile(
            "(?:public|private|protected|internal)\\s+\\S+\\s+(\\w+)\\s*\\(");

    @Override
    public String getFrameworkName() { return "ASP.NET Core"; }

    @Override
    public boolean supports(Path repositoryRoot) {
        try {
            Optional<Path> csproj = Files.walk(repositoryRoot, 2)
                    .filter(p -> p.toString().endsWith(".csproj"))
                    .findFirst();
            if (csproj.isEmpty()) return false;
            String content = Files.readString(csproj.get());
            return content.contains("Microsoft.AspNetCore") || content.contains("net");
        } catch (IOException e) { return false; }
    }

    @Override
    public List<ExtractedApi> extract(Path repositoryRoot) {
        List<ExtractedApi> apis = new ArrayList<>();
        try {
            Files.walk(repositoryRoot)
                    .filter(p -> p.toString().endsWith(".cs"))
                    .filter(p -> !p.toString().contains("obj") && !p.toString().contains("bin"))
                    .filter(p -> !p.getFileName().toString().contains("Test"))
                    .forEach(f -> {
                        try { apis.addAll(parseFile(f)); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
        return apis;
    }

    private List<ExtractedApi> parseFile(Path file) throws IOException {
        String content = Files.readString(file);
        if (!content.contains("[ApiController]") && !content.contains("ControllerBase")) {
            return Collections.emptyList();
        }

        List<ExtractedApi> apis = new ArrayList<>();
        String[] lines = content.split("\\r?\\n");

        String className = firstMatch(CLASS_NAME, content, 1);
        if (className != null && className.endsWith("Controller")) {
            className = className.substring(0, className.length() - "Controller".length());
        }

        // Resolve class-level [Route] – replace [controller] token
        String baseRoute = firstMatch(ROUTE_ATTR, content, 1);
        if (baseRoute != null && className != null) {
            baseRoute = baseRoute.replace("[controller]", className.toLowerCase());
        }
        if (baseRoute == null) baseRoute = "";

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            Matcher m = HTTP_METHOD.matcher(line);
            if (!m.find()) continue;

            String httpMethod  = m.group(1).toUpperCase();
            String methodRoute = m.group(2) != null ? m.group(2) : "";

            // Method-level [Route] override (on same or adjacent line)
            String overrideRoute = null;
            for (int j = i - 1; j <= i + 1; j++) {
                if (j < 0 || j >= lines.length) continue;
                Matcher rm = ROUTE_ATTR.matcher(lines[j].trim());
                if (rm.find()) { overrideRoute = rm.group(1); break; }
            }
            String methodPath = overrideRoute != null ? overrideRoute : methodRoute;

            // Method name
            String handlerName = null;
            for (int j = i + 1; j < Math.min(i + 6, lines.length); j++) {
                handlerName = firstMatch(METHOD_SIG, lines[j].trim(), 1);
                if (handlerName != null) break;
            }

            ExtractedApi api = new ExtractedApi();
            api.setMethod(httpMethod);
            api.setPath(joinPaths(baseRoute, methodPath));
            api.setController(className);
            api.setHandler(handlerName);
            apis.add(api);
        }
        return apis;
    }

    private String joinPaths(String base, String path) {
        String b = base == null ? "" : base.replaceAll("/$", "");
        String p = path == null ? "" : (path.startsWith("/") ? path : (path.isEmpty() ? "" : "/" + path));
        String joined = b + p;
        return joined.isEmpty() ? "/" : (joined.startsWith("/") ? joined : "/" + joined);
    }

    private String firstMatch(Pattern pattern, String text, int group) {
        Matcher m = pattern.matcher(text);
        if (m.find()) { try { return m.group(group); } catch (Exception ignored) {} }
        return null;
    }
}
