package com.apicatalog.parser.jaxrs;

import com.apicatalog.model.ApiField;
import com.apicatalog.model.ApiParameter;
import com.apicatalog.model.ExtractedApi;
import com.apicatalog.parser.ParserPlugin;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Parser for JAX-RS projects (Jersey, RESTEasy, Quarkus REST).
 * Handles @GET/@POST/etc. + @Path on classes and methods.
 */
@Component
public class JaxRsParser implements ParserPlugin {

    private static final Pattern HTTP_METHOD  = Pattern.compile(
            "^@(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)$");
    private static final Pattern PATH_ANN     = Pattern.compile(
            "@Path\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)");
    private static final Pattern CLASS_NAME   = Pattern.compile(
            "(?:public|protected|private)?\\s+class\\s+(\\w+)");
    private static final Pattern METHOD_SIG   = Pattern.compile(
            "(?:public|protected|private)\\s+\\S+\\s+(\\w+)\\s*\\(");
    private static final Pattern PATH_PARAM   = Pattern.compile(
            "@PathParam\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)\\s+\\S+\\s+(\\w+)");
    private static final Pattern QUERY_PARAM  = Pattern.compile(
            "@QueryParam\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)\\s+\\S+\\s+(\\w+)");
    private static final Pattern HEADER_PARAM = Pattern.compile(
            "@HeaderParam\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)\\s+\\S+\\s+(\\w+)");

    @Override public String getFrameworkName() { return "JAX-RS"; }

    @Override
    public boolean supports(Path root) {
        for (String f : List.of("pom.xml", "build.gradle", "build.gradle.kts")) {
            Path p = root.resolve(f);
            if (Files.exists(p)) {
                try {
                    String c = Files.readString(p).toLowerCase();
                    if (c.contains("jax-rs") || c.contains("jakarta.ws.rs")
                            || c.contains("javax.ws.rs") || c.contains("jersey")
                            || c.contains("resteasy") || c.contains("quarkus-rest"))
                        return true;
                } catch (IOException ignored) {}
            }
        }
        return false;
    }

    @Override
    public List<ExtractedApi> extract(Path root) {
        List<ExtractedApi> apis = new ArrayList<>();
        try {
            Files.walk(root)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains(File.separator + "test" + File.separator))
                    .forEach(f -> {
                        try { apis.addAll(parseFile(f)); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
        return apis;
    }

    private List<ExtractedApi> parseFile(Path file) throws IOException {
        String content = Files.readString(file);
        if (!content.contains("@Path")) return Collections.emptyList();

        String[] lines = content.split("\\r?\\n");
        String className = firstMatch(CLASS_NAME, content, 1);
        String basePath  = firstMatch(PATH_ANN, content, 1);
        if (basePath == null) basePath = "";

        List<ExtractedApi> apis = new ArrayList<>();
        List<String> pending = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.isEmpty() || t.startsWith("//") || t.startsWith("*")) continue;
            if (t.startsWith("@")) { pending.add(t); continue; }

            if (!pending.isEmpty()) {
                String httpMethod  = null;
                String methodPath  = null;

                for (String ann : pending) {
                    Matcher hm = HTTP_METHOD.matcher(ann);
                    if (hm.find()) httpMethod = hm.group(1);
                    Matcher pm = PATH_ANN.matcher(ann);
                    if (pm.find()) methodPath = pm.group(1);
                }

                if (httpMethod != null) {
                    String handlerName = firstMatch(METHOD_SIG, t, 1);

                    // Collect params from this line and next few lines
                    List<ApiParameter> params = new ArrayList<>();
                    StringBuilder paramBlock = new StringBuilder(t);
                    for (int j = i + 1; j < Math.min(i + 8, lines.length); j++) {
                        String pl = lines[j].trim();
                        if (pl.startsWith("{") || pl.isEmpty()) break;
                        paramBlock.append(" ").append(pl);
                    }
                    String pb = paramBlock.toString();
                    addParams(pb, PATH_PARAM,   "PATH",   params);
                    addParams(pb, QUERY_PARAM,  "QUERY",  params);
                    addParams(pb, HEADER_PARAM, "HEADER", params);

                    ExtractedApi api = new ExtractedApi();
                    api.setMethod(httpMethod);
                    api.setPath(joinPaths(basePath, methodPath != null ? methodPath : ""));
                    api.setController(className);
                    api.setHandler(handlerName);
                    api.setParameters(params.isEmpty() ? null : params);
                    api.setSourceLine(i + 1);
                    apis.add(api);
                }
            }
            pending.clear();
        }
        return apis;
    }

    private void addParams(String text, Pattern p, String location, List<ApiParameter> out) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            ApiParameter param = new ApiParameter();
            param.setName(m.group(1));
            param.setLocation(location);
            param.setRequired("PATH".equals(location));
            out.add(param);
        }
    }

    private String joinPaths(String base, String path) {
        if (base == null) base = ""; if (path == null) path = "";
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String p = path.startsWith("/") ? path : (path.isEmpty() ? "" : "/" + path);
        String j = b + p; return j.isEmpty() ? "/" : (j.startsWith("/") ? j : "/" + j);
    }

    private String firstMatch(Pattern pattern, String text, int group) {
        Matcher m = pattern.matcher(text);
        if (m.find()) { try { return m.group(group); } catch (Exception ignored) {} }
        return null;
    }
}
