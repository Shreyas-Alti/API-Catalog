package com.apicatalog.parser.fastify;

import com.apicatalog.model.*;
import com.apicatalog.parser.ParserPlugin;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

@Component
public class FastifyParser implements ParserPlugin {

    // ── Shorthand style: fastify.get('/path', handler) ─────────────────────
    private static final Pattern ROUTE = Pattern.compile(
        "^\\s*(?:[\\w$]+\\.)?(get|post|put|delete|patch)\\s*\\(\\s*['\"`]([^'\"`]+)['\"`]",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern HANDLER_ARG = Pattern.compile(",\\s*([A-Za-z_$][\\w$]*)\\s*[,)]");

    // ── Route-object style: fastify.route({ method, url, handler, schema }) ─
    private static final Pattern ROUTE_OBJ       = Pattern.compile(
        "^\\s*[\\w$]+\\.route\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROUTE_METHOD_KV = Pattern.compile(
        "\\bmethod\\s*:\\s*['\"`](GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)['\"`]",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ROUTE_URL_KV    = Pattern.compile(
        "\\b(?:url|path)\\s*:\\s*['\"`]([^'\"`]+)['\"`]");
    private static final Pattern ROUTE_HANDLER_KV= Pattern.compile(
        "\\bhandler\\s*:\\s*([A-Za-z_$][\\w$]*)");

    // ── Schema extraction (shared) ──────────────────────────────────────────
    private static final Pattern SCHEMA_BLOCK    = Pattern.compile("schema\\s*:\\s*\\{([^}]*)\\}", Pattern.DOTALL);
    private static final Pattern SCHEMA_SECTION  = Pattern.compile("(body|querystring|params|headers|response)\\s*:\\s*\\{", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCHEMA_PROP     = Pattern.compile("['\"](\\w+)['\"]\\s*:\\s*\\{[^}]*type\\s*:\\s*['\"]([^'\"]+)['\"]\\}");
    private static final Pattern SCHEMA_REQUIRED = Pattern.compile("required\\s*:\\s*\\[([^\\]]+)\\]");
    private static final Pattern REPLY_STATUS    = Pattern.compile("reply\\.(?:status|code)\\s*\\(\\s*(\\d{3})\\s*\\)");
    private static final Pattern URL_PARAM       = Pattern.compile(":(\\w+)");
    private static final Pattern JSDOC_LINE      = Pattern.compile("^\\s*\\*\\s*(?!@)(\\w.+)$");

    private static final Set<String> KEYWORDS = Set.of(
        "request","reply","req","res","next","done","opts","options","fastify","server","app");

    @Override public String getFrameworkName() { return "Fastify"; }

    @Override
    public boolean supports(Path root) {
        Path pkg = root.resolve("package.json");
        if (!Files.exists(pkg)) return false;
        try { return Files.readString(pkg).contains("\"fastify\""); }
        catch (IOException e) { return false; }
    }

    @Override
    public List<ExtractedApi> extract(Path root) {
        List<ExtractedApi> apis = new ArrayList<>();
        try {
            Files.walk(root)
                .filter(p -> { String s = p.toString(); return (s.endsWith(".js") || s.endsWith(".ts") || s.endsWith(".mjs")) && !s.contains("node_modules") && !s.contains(".test.") && !s.contains(".spec."); })
                .forEach(f -> { try { apis.addAll(parseFile(f, root)); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
        return apis;
    }

    private List<ExtractedApi> parseFile(Path file, Path root) throws IOException {
        String[] lines = Files.readAllLines(file).toArray(new String[0]);
        List<ExtractedApi> apis = new ArrayList<>();
        String relPath = root.relativize(file).toString().replace(java.io.File.separatorChar, '/');

        for (int i = 0; i < lines.length; i++) {
            // ── 1. Shorthand style: fastify.get('/path', opts, handler) ────
            Matcher m = ROUTE.matcher(lines[i]);
            if (m.find()) {
                String httpMethod = m.group(1).toUpperCase();
                String path = m.group(2);
                String handler = null;
                String after = lines[i].substring(m.end());
                Matcher hm = HANDLER_ARG.matcher(after);
                String last = null; while (hm.find()) last = hm.group(1);
                if (last != null && !KEYWORDS.contains(last)) handler = last;

                String desc = jsDocAbove(lines, i);
                String routeBlock = collectBlock(lines, i, 65);
                ExtractedApi api = buildApi(httpMethod, path, handler, desc, routeBlock, relPath, i + 1);
                if (api != null) apis.add(api);
                continue;
            }

            // ── 2. Route-object style: fastify.route({ method, url, handler }) ──
            if (ROUTE_OBJ.matcher(lines[i]).find()) {
                String block = collectParenBlock(lines, i);
                if (block == null) continue;

                Matcher mm = ROUTE_METHOD_KV.matcher(block);
                if (!mm.find()) continue;
                String httpMethod = mm.group(1).toUpperCase();

                Matcher um = ROUTE_URL_KV.matcher(block);
                if (!um.find()) continue;
                String path = um.group(1);

                String handler = null;
                Matcher handlerM = ROUTE_HANDLER_KV.matcher(block);
                if (handlerM.find()) handler = handlerM.group(1);

                String desc = jsDocAbove(lines, i);
                ExtractedApi api = buildApi(httpMethod, path, handler, desc, block, relPath, i + 1);
                if (api != null) apis.add(api);
            }
        }
        return apis;
    }

    /** Build an ExtractedApi from parsed fields, extracting schema + status codes from routeBlock. */
    private ExtractedApi buildApi(String httpMethod, String path, String handler,
                                   String desc, String routeBlock,
                                   String relPath, int lineNum) {
        List<ApiParameter> params = new ArrayList<>();
        Set<String> seenPath = new HashSet<>();
        Matcher urlPm = URL_PARAM.matcher(path);
        while (urlPm.find()) {
            String pn = urlPm.group(1); seenPath.add(pn);
            ApiParameter p = new ApiParameter(); p.setName(pn); p.setType("string"); p.setLocation("PATH"); p.setRequired(true); params.add(p);
        }
        String reqBodyType = null; List<ApiField> reqBodyFields = List.of();
        List<Integer> codes = new ArrayList<>();

        if (routeBlock != null) {
            Matcher schemaM = SCHEMA_BLOCK.matcher(routeBlock);
            if (schemaM.find()) {
                String schemaContent = schemaM.group(1);
                Matcher sectionM = SCHEMA_SECTION.matcher(schemaContent);
                int sPos = 0;
                while (sectionM.find(sPos)) {
                    String sectionName = sectionM.group(1).toLowerCase();
                    String sectionContent = extractObjectBlock(schemaContent, sectionM.end() - 1);
                    Set<String> requiredFields = new HashSet<>();
                    Matcher reqM = SCHEMA_REQUIRED.matcher(sectionContent);
                    if (reqM.find()) { Matcher qm = Pattern.compile("['\"](\\w+)['\"]\\s*").matcher(reqM.group(1)); while (qm.find()) requiredFields.add(qm.group(1)); }
                    Matcher propM = SCHEMA_PROP.matcher(sectionContent);
                    List<ApiField> sectionFields = new ArrayList<>();
                    while (propM.find()) { ApiField f = new ApiField(); f.setName(propM.group(1)); f.setType(propM.group(2)); sectionFields.add(f); }
                    switch (sectionName) {
                        case "body" -> { reqBodyType = "object"; reqBodyFields = sectionFields; for (ApiField f : sectionFields) { ApiParameter p = new ApiParameter(); p.setName(f.getName()); p.setType(f.getType()); p.setLocation("BODY"); p.setRequired(requiredFields.contains(f.getName())); params.add(p); } }
                        case "querystring" -> { for (ApiField f : sectionFields) { ApiParameter p = new ApiParameter(); p.setName(f.getName()); p.setType(f.getType()); p.setLocation("QUERY"); p.setRequired(requiredFields.contains(f.getName())); params.add(p); } }
                        case "params" -> { for (ApiField f : sectionFields) { if (!seenPath.contains(f.getName())) { ApiParameter p = new ApiParameter(); p.setName(f.getName()); p.setType(f.getType()); p.setLocation("PATH"); p.setRequired(true); params.add(p); seenPath.add(f.getName()); } } }
                        case "headers" -> { for (ApiField f : sectionFields) { ApiParameter p = new ApiParameter(); p.setName(f.getName()); p.setType(f.getType()); p.setLocation("HEADER"); p.setRequired(requiredFields.contains(f.getName())); params.add(p); } }
                        case "response" -> { Matcher cm = Pattern.compile("['\"](\\d{3})['\"]\\s*:").matcher(sectionContent); while (cm.find()) { try { int code = Integer.parseInt(cm.group(1)); if (!codes.contains(code)) codes.add(code); } catch (Exception ignored) {} } }
                    }
                    sPos = sectionM.end();
                }
            }
            Matcher sm = REPLY_STATUS.matcher(routeBlock);
            while (sm.find()) { try { int code = Integer.parseInt(sm.group(1)); if (!codes.contains(code)) codes.add(code); } catch (Exception ignored) {} }
        }

        ExtractedApi api = new ExtractedApi();
        api.setMethod(httpMethod); api.setPath(path); api.setHandler(handler); api.setDescription(desc);
        api.setParameters(params.isEmpty() ? null : params);
        api.setRequestBodyType(reqBodyType); api.setRequestBodyFields(reqBodyFields.isEmpty() ? null : reqBodyFields);
        api.setStatusCodes(codes.isEmpty() ? null : codes);
        api.setSourceFile(relPath); api.setSourceLine(lineNum);
        return api;
    }

    /** Collect up to maxLines of source as a single string (for schema scanning). */
    private String collectBlock(String[] lines, int start, int maxLines) {
        StringBuilder sb = new StringBuilder();
        int depth = 0; boolean inBlock = false;
        for (int j = start; j < Math.min(start + maxLines, lines.length); j++) {
            String bl = lines[j]; sb.append(bl).append("\n");
            for (char c : bl.toCharArray()) { if (c == '{') { inBlock = true; depth++; } else if (c == '}') { depth--; if (inBlock && depth == 0) return sb.toString(); } }
        }
        return sb.toString();
    }

    /** Collect the content inside the outermost parentheses starting at the given line. */
    private String collectParenBlock(String[] lines, int start) {
        int depth = 0; boolean started = false;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < Math.min(start + 80, lines.length); i++) {
            for (char c : lines[i].toCharArray()) {
                if (c == '(') { started = true; depth++; if (depth == 1) continue; }
                else if (c == ')') { depth--; if (started && depth == 0) return sb.toString(); }
                if (started && depth >= 1) sb.append(c);
            }
            if (started) sb.append('\n');
        }
        return started ? sb.toString() : null;
    }

    private String extractObjectBlock(String s, int pos) {
        int start = s.indexOf('{', pos); if (start < 0) return "";
        int d = 0; StringBuilder sb = new StringBuilder();
        for (int i = start; i < s.length(); i++) { char c = s.charAt(i); sb.append(c); if (c == '{') d++; else if (c == '}') { d--; if (d == 0) break; } }
        return sb.toString();
    }

    private String jsDocAbove(String[] lines, int routeLine) {
        for (int i = routeLine - 1; i >= Math.max(0, routeLine - 8); i--) {
            String line = lines[i].trim();
            if (line.endsWith("*/")) {
                for (int j = i - 1; j >= Math.max(0, i - 10); j--) {
                    if (lines[j].trim().startsWith("/*")) {
                        for (int k = j + 1; k <= i; k++) { Matcher dm = JSDOC_LINE.matcher(lines[k]); if (dm.find()) return dm.group(1).trim(); }
                        return null;
                    }
                }
            }
            if (!line.isEmpty() && !line.startsWith("*") && !line.startsWith("//")) break;
        }
        return null;
    }
}
