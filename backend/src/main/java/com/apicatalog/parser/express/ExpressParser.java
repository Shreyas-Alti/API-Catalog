package com.apicatalog.parser.express;

import com.apicatalog.model.*;
import com.apicatalog.parser.ParserPlugin;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

@Component
public class ExpressParser implements ParserPlugin {

    private static final Pattern ROUTE = Pattern.compile(
        "^\\s*(?:[\\w$]+\\.)?(get|post|put|delete|patch)\\s*\\(\\s*['\"`]([^'\"`]+)['\"`]",
        Pattern.CASE_INSENSITIVE);
    // Matches: router.route('/path') — the base of a chained-method definition
    private static final Pattern ROUTE_BASE = Pattern.compile(
        "\\.route\\s*\\(\\s*['\"`]([^'\"`]+)['\"`]\\s*\\)",
        Pattern.CASE_INSENSITIVE);
    // Matches: .get(args), .post(args), … inside a .route() chain
    private static final Pattern CHAIN_METHOD = Pattern.compile(
        "\\.\\s*(get|post|put|delete|patch)\\s*\\(([^)]*)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern HANDLER_ARG = Pattern.compile(",\\s*([A-Za-z_$][\\w$]*)\\s*[,)]");
    private static final Pattern REQ_PARAM = Pattern.compile("req\\.params(?:\\.(\\w+)|\\[['\"]([\\w]+)['\"]\\])");
    private static final Pattern REQ_QUERY = Pattern.compile("req\\.query(?:\\.(\\w+)|\\[['\"]([\\w]+)['\"]\\])");
    private static final Pattern BODY_DESTRUCT = Pattern.compile("(?:const|let|var)\\s*\\{([^}]+)\\}\\s*=\\s*req\\.body");
    private static final Pattern RES_STATUS = Pattern.compile("res\\.(?:status|sendStatus)\\s*\\(\\s*(\\d{3})\\s*\\)");
    // res.json({ key: val, key2: val2 }) — extract top-level keys as response field names
    private static final Pattern RES_JSON = Pattern.compile("res\\.(?:json|send)\\s*\\(\\s*\\{([^}]{0,300})\\}");
    private static final Pattern RES_JSON_KEY = Pattern.compile("([a-zA-Z_$][\\w$]*)\\s*:");
    private static final Pattern URL_PARAM = Pattern.compile(":(\\w+)");
    private static final Pattern JSDOC_LINE = Pattern.compile("^\\s*\\*\\s*(?!@)(\\w.+)$");

    // Best-effort response type from TypeScript return annotation or JSDoc @returns
    private static final Pattern TS_RETURN_TYPE = Pattern.compile(":\\s*Promise<([\\w<>\\[\\]]+)>");
    private static final Pattern JSDOC_RETURNS   = Pattern.compile("@returns?\\s*\\{([\\w<>\\[\\]]+)\\}");

    private static final Set<String> KEYWORDS = Set.of(
        "req","res","next","err","null","true","false","async","function",
        "return","const","let","var","router","app","server","middleware","exports");

    @Override public String getFrameworkName() { return "Express"; }

    @Override
    public boolean supports(Path root) {
        Path pkg = root.resolve("package.json");
        if (!Files.exists(pkg)) return false;
        try {
            String c = Files.readString(pkg);
            return c.contains("\"express\"") && !c.contains("\"@nestjs/core\"") && !c.contains("\"fastify\"");
        } catch (IOException e) { return false; }
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
        String controller = deriveController(file);
        Set<Integer> skip = new HashSet<>(); // lines consumed by .route() chain continuation

        for (int i = 0; i < lines.length; i++) {
            if (skip.contains(i)) continue;

            // ── Path 1: router.get('/path', handler) — inline method ──────────
            Matcher m = ROUTE.matcher(lines[i]);
            if (m.find()) {
                String httpMethod = m.group(1).toUpperCase();
                String path = m.group(2);
                String handler = null;
                String after = lines[i].substring(m.end());
                Matcher hm = HANDLER_ARG.matcher(after);
                String lastCand = null;
                while (hm.find()) lastCand = hm.group(1);
                if (lastCand != null && !KEYWORDS.contains(lastCand)) handler = lastCand;
                String desc = jsDocAbove(lines, i);
                List<ApiParameter> params = new ArrayList<>();
                Set<String> seenPath = new HashSet<>();
                Matcher urlPm = URL_PARAM.matcher(path);
                while (urlPm.find()) {
                    String pn = urlPm.group(1); seenPath.add(pn);
                    ApiParameter p = new ApiParameter(); p.setName(pn); p.setType("string"); p.setLocation("PATH"); p.setRequired(true); params.add(p);
                }
                Set<String> queryNames = new LinkedHashSet<>();
                boolean hasBody = false; Set<String> bodyFields = new LinkedHashSet<>();
                List<Integer> codes = new ArrayList<>();
                Set<String> responseFieldNames = new LinkedHashSet<>();
                for (int j = i + 1; j < Math.min(i + 45, lines.length); j++) {
                    String bl = lines[j];
                    if (j > i + 2 && ROUTE.matcher(bl).find()) break;
                    Matcher rpm = REQ_PARAM.matcher(bl);
                    while (rpm.find()) {
                        String pn = rpm.group(1) != null ? rpm.group(1) : rpm.group(2);
                        if (pn != null && !seenPath.contains(pn)) { ApiParameter p = new ApiParameter(); p.setName(pn); p.setType("string"); p.setLocation("PATH"); p.setRequired(true); params.add(p); seenPath.add(pn); }
                    }
                    Matcher qm = REQ_QUERY.matcher(bl);
                    while (qm.find()) { String qn = qm.group(1) != null ? qm.group(1) : qm.group(2); if (qn != null) queryNames.add(qn); }
                    if (bl.contains("req.body")) {
                        hasBody = true;
                        Matcher dm = BODY_DESTRUCT.matcher(bl);
                        if (dm.find()) { for (String f : dm.group(1).split(",")) { String fn = f.trim().replaceAll("\\s*=.*", "").trim(); if (!fn.isEmpty()) bodyFields.add(fn); } }
                    }
                    Matcher sm = RES_STATUS.matcher(bl);
                    while (sm.find()) { try { int code = Integer.parseInt(sm.group(1)); if (!codes.contains(code)) codes.add(code); } catch (NumberFormatException ignored) {} }
                    // Extract top-level keys from res.json({key: ...}) for response schema
                    Matcher rjm = RES_JSON.matcher(bl);
                    if (rjm.find()) {
                        Matcher km = RES_JSON_KEY.matcher(rjm.group(1));
                        while (km.find()) responseFieldNames.add(km.group(1));
                    }
                }
                for (String qn : queryNames) { ApiParameter p = new ApiParameter(); p.setName(qn); p.setType("string"); p.setLocation("QUERY"); p.setRequired(false); params.add(p); }
                String reqBodyType = null; List<ApiField> reqBodyFields = List.of();
                if (hasBody && (httpMethod.equals("POST") || httpMethod.equals("PUT") || httpMethod.equals("PATCH"))) {
                    ApiParameter bp = new ApiParameter(); bp.setName("body"); bp.setType("object"); bp.setLocation("BODY"); bp.setRequired(true); params.add(bp);
                    reqBodyType = "object";
                    if (!bodyFields.isEmpty()) {
                        List<ApiField> fList = new ArrayList<>();
                        for (String fn : bodyFields) { ApiField f = new ApiField(); f.setName(fn); f.setType("any"); fList.add(f); }
                        reqBodyFields = fList;
                    }
                }
                // Response body — build from res.json() keys when available, then try TS/JSDoc type
                String respBodyType = null; List<ApiField> respBodyFields = List.of();
                if (!responseFieldNames.isEmpty()) {
                    respBodyType = "object";
                    List<ApiField> fList = new ArrayList<>();
                    for (String fn : responseFieldNames) { ApiField f = new ApiField(); f.setName(fn); f.setType("any"); fList.add(f); }
                    respBodyFields = fList;
                } else {
                    // Fall back to TS return type or JSDoc @returns (leaves null for plain JS — correct)
                    respBodyType = extractResponseType(lines[i], desc);
                }
                ExtractedApi api = new ExtractedApi();
                api.setMethod(httpMethod); api.setPath(path); api.setController(controller); api.setHandler(handler); api.setDescription(desc);
                api.setTags(List.of(controller));
                api.setParameters(params.isEmpty() ? null : params);
                api.setRequestBodyType(reqBodyType); api.setRequestBodyFields(reqBodyFields.isEmpty() ? null : reqBodyFields);
                api.setResponseBodyType(respBodyType); api.setResponseBodyFields(respBodyFields.isEmpty() ? null : respBodyFields);
                api.setStatusCodes(codes.isEmpty() ? null : codes);
                api.setSourceFile(relPath); api.setSourceLine(i + 1);
                apis.add(api);
                continue;
            }

            // ── Path 2: router.route('/path').get(h1).post(h2) — chained ────
            Matcher rb = ROUTE_BASE.matcher(lines[i]);
            if (!rb.find()) continue;

            String basePath = rb.group(1);
            String desc = jsDocAbove(lines, i);

            // Collect chain text: remainder of current line + subsequent lines starting with '.'
            StringBuilder chainBuf = new StringBuilder(lines[i].substring(rb.end()));
            for (int j = i + 1; j < Math.min(i + 10, lines.length); j++) {
                String t = lines[j].trim();
                if (t.startsWith(".")) { chainBuf.append(t); skip.add(j); }
                else break;
            }

            // URL path params (shared across all methods in the chain)
            List<ApiParameter> baseParams = new ArrayList<>();
            Matcher urlPm = URL_PARAM.matcher(basePath);
            while (urlPm.find()) {
                ApiParameter p = new ApiParameter(); p.setName(urlPm.group(1)); p.setType("string"); p.setLocation("PATH"); p.setRequired(true); baseParams.add(p);
            }

            // Emit one ExtractedApi per HTTP method in the chain
            Matcher cm = CHAIN_METHOD.matcher(chainBuf);
            while (cm.find()) {
                String method = cm.group(1).toUpperCase();
                String handler = lastIdentifier(cm.group(2) != null ? cm.group(2) : "");
                ExtractedApi api = new ExtractedApi();
                api.setMethod(method); api.setPath(basePath); api.setController(controller); api.setHandler(handler); api.setDescription(desc);
                api.setTags(List.of(controller));
                api.setDescription(desc);
                api.setParameters(baseParams.isEmpty() ? null : new ArrayList<>(baseParams));
                api.setSourceFile(relPath); api.setSourceLine(i + 1);
                apis.add(api);
            }
        }
        return apis;
    }

    /** Returns the last non-keyword identifier found in an argument string. */
    private String lastIdentifier(String args) {
        Matcher idm = Pattern.compile("([A-Za-z_$][\\w$]*)").matcher(args);
        String last = null;
        while (idm.find()) { String c = idm.group(1); if (!KEYWORDS.contains(c)) last = c; }
        return last;
    }

    /** "user-routes.ts" → "UserRoutes" */
    private String deriveController(Path file) {
        String name = file.getFileName().toString().replaceAll("\\.(js|ts|mjs)$", "");
        return Arrays.stream(name.split("[-_.]"))
                .filter(w -> !w.isEmpty())
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1))
                .collect(Collectors.joining());
    }

    /** TS Promise<T> return type or JSDoc @returns {T}, else null (correct for plain JS). */
    private String extractResponseType(String signatureLine, String precedingJsDoc) {
        Matcher tm = TS_RETURN_TYPE.matcher(signatureLine);
        if (tm.find()) return tm.group(1);
        if (precedingJsDoc != null) {
            Matcher jm = JSDOC_RETURNS.matcher(precedingJsDoc);
            if (jm.find()) return jm.group(1);
        }
        return null;
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