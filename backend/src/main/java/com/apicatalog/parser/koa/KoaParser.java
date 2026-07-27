package com.apicatalog.parser.koa;

import com.apicatalog.model.*;
import com.apicatalog.parser.ParserPlugin;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Parser for Koa (Node.js) projects using @koa/router or koa-router.
 * Handles both inline registration (router.get('/path', h)) and
 * Express-style chained registration (router.route('/path').get(h).post(h)).
 */
@Component
public class KoaParser implements ParserPlugin {

    // Direct: router.get('/path', handler)
    private static final Pattern ROUTE = Pattern.compile(
            "^\\s*(?:[\\w$]+\\.)?(get|post|put|delete|patch)\\s*\\(\\s*['\"`]([^'\"`]+)['\"`]",
            Pattern.CASE_INSENSITIVE);
    // Chained base: router.route('/path') or .route('/path')
    private static final Pattern ROUTE_BASE = Pattern.compile(
            "\\.route\\s*\\(\\s*['\"`]([^'\"`]+)['\"`]\\s*\\)",
            Pattern.CASE_INSENSITIVE);
    // Chained method: .get(...) or .post(...) after a .route()
    private static final Pattern CHAIN_METHOD = Pattern.compile(
            "\\.\\s*(get|post|put|delete|patch)\\s*\\(([^)]*)",
            Pattern.CASE_INSENSITIVE);
    // Named handler argument
    private static final Pattern HANDLER_ARG = Pattern.compile(",\\s*([A-Za-z_$][\\w$]*)\\s*[,)]");

    // Koa context access patterns (ctx instead of req/res)
    private static final Pattern CTX_PARAMS = Pattern.compile(
            "ctx\\.params(?:\\.(\\w+)|\\[['\"]([\\w]+)['\"]\\])");
    private static final Pattern CTX_QUERY = Pattern.compile(
            "ctx\\.(?:query|request\\.query)(?:\\.(\\w+)|\\[['\"]([\\w]+)['\"]\\])");
    private static final Pattern CTX_BODY = Pattern.compile(
            "ctx\\.(?:request\\.body|body)");
    private static final Pattern CTX_STATUS = Pattern.compile(
            "ctx\\.(?:status|response\\.status)\\s*=\\s*(\\d{3})");

    private static final Pattern URL_PARAM = Pattern.compile(":(\\w+)");
    private static final Pattern JSDOC_LINE = Pattern.compile("^\\s*\\*\\s*(?!@)(\\w.+)$");

    private static final Set<String> KEYWORDS = Set.of(
            "ctx","context","next","err","null","true","false","async","function",
            "return","const","let","var","router","app","middleware");

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
                        try { apis.addAll(parseFile(f, root)); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
        return apis;
    }

    private List<ExtractedApi> parseFile(Path file, Path root) throws IOException {
        String[] lines = Files.readAllLines(file).toArray(new String[0]);
        List<ExtractedApi> apis = new ArrayList<>();
        String relPath = root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
        Set<Integer> skip = new HashSet<>();

        for (int i = 0; i < lines.length; i++) {
            if (skip.contains(i)) continue;

            // ── Path 1: router.get('/path', handler) ─────────────────────────
            Matcher m = ROUTE.matcher(lines[i]);
            if (m.find()) {
                String httpMethod = m.group(1).toUpperCase();
                String path = m.group(2);

                // Handler: last non-keyword identifier before closing paren
                String handler = null;
                String after = lines[i].substring(m.end());
                Matcher hm = HANDLER_ARG.matcher(after);
                String lastCand = null;
                while (hm.find()) lastCand = hm.group(1);
                if (lastCand != null && !KEYWORDS.contains(lastCand)) handler = lastCand;

                String desc = jsDocAbove(lines, i);
                List<ApiParameter> params = extractPathParams(path);
                Set<String> seenPath = new HashSet<>(); params.forEach(p -> seenPath.add(p.getName()));
                List<Integer> codes = new ArrayList<>();
                Set<String> queryNames = new LinkedHashSet<>();
                boolean hasBody = false;

                for (int j = i + 1; j < Math.min(i + 45, lines.length); j++) {
                    String bl = lines[j];
                    if (j > i + 2 && ROUTE.matcher(bl).find()) break;
                    Matcher pm = CTX_PARAMS.matcher(bl);
                    while (pm.find()) {
                        String pn = pm.group(1) != null ? pm.group(1) : pm.group(2);
                        if (pn != null && !seenPath.contains(pn)) {
                            ApiParameter p = new ApiParameter(); p.setName(pn); p.setType("string"); p.setLocation("PATH"); p.setRequired(true);
                            params.add(p); seenPath.add(pn);
                        }
                    }
                    Matcher qm = CTX_QUERY.matcher(bl);
                    while (qm.find()) { String qn = qm.group(1) != null ? qm.group(1) : qm.group(2); if (qn != null) queryNames.add(qn); }
                    if (CTX_BODY.matcher(bl).find()) hasBody = true;
                    Matcher sm = CTX_STATUS.matcher(bl);
                    while (sm.find()) { try { int code = Integer.parseInt(sm.group(1)); if (!codes.contains(code)) codes.add(code); } catch (NumberFormatException ignored) {} }
                }
                for (String qn : queryNames) {
                    ApiParameter p = new ApiParameter(); p.setName(qn); p.setType("string"); p.setLocation("QUERY"); p.setRequired(false); params.add(p);
                }
                String reqBodyType = null;
                if (hasBody && (httpMethod.equals("POST") || httpMethod.equals("PUT") || httpMethod.equals("PATCH"))) {
                    ApiParameter bp = new ApiParameter(); bp.setName("body"); bp.setType("object"); bp.setLocation("BODY"); bp.setRequired(true); params.add(bp);
                    reqBodyType = "object";
                }
                ExtractedApi api = new ExtractedApi();
                api.setMethod(httpMethod); api.setPath(path); api.setHandler(handler); api.setDescription(desc);
                api.setParameters(params.isEmpty() ? null : params);
                api.setRequestBodyType(reqBodyType);
                api.setStatusCodes(codes.isEmpty() ? null : codes);
                api.setSourceFile(relPath); api.setSourceLine(i + 1);
                apis.add(api);
                continue;
            }

            // ── Path 2: router.route('/path').get(h).post(h) — chained ──────
            Matcher rb = ROUTE_BASE.matcher(lines[i]);
            if (!rb.find()) continue;

            String basePath = rb.group(1);
            String desc = jsDocAbove(lines, i);

            StringBuilder chainBuf = new StringBuilder(lines[i].substring(rb.end()));
            for (int j = i + 1; j < Math.min(i + 10, lines.length); j++) {
                String t = lines[j].trim();
                if (t.startsWith(".")) { chainBuf.append(t); skip.add(j); }
                else break;
            }

            List<ApiParameter> baseParams = extractPathParams(basePath);

            Matcher cm = CHAIN_METHOD.matcher(chainBuf);
            while (cm.find()) {
                String method = cm.group(1).toUpperCase();
                String handler = lastIdentifier(cm.group(2) != null ? cm.group(2) : "");
                ExtractedApi api = new ExtractedApi();
                api.setMethod(method); api.setPath(basePath); api.setHandler(handler); api.setDescription(desc);
                api.setParameters(baseParams.isEmpty() ? null : new ArrayList<>(baseParams));
                api.setSourceFile(relPath); api.setSourceLine(i + 1);
                apis.add(api);
            }
        }
        return apis;
    }

    private List<ApiParameter> extractPathParams(String path) {
        List<ApiParameter> params = new ArrayList<>();
        Matcher m = URL_PARAM.matcher(path);
        while (m.find()) {
            ApiParameter p = new ApiParameter(); p.setName(m.group(1)); p.setType("string"); p.setLocation("PATH"); p.setRequired(true); params.add(p);
        }
        return params;
    }

    private String lastIdentifier(String s) {
        Matcher m = Pattern.compile("\\b([A-Za-z_$][\\w$]*)\\b").matcher(s);
        String last = null;
        while (m.find()) { if (!KEYWORDS.contains(m.group(1))) last = m.group(1); }
        return last;
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