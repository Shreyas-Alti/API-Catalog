package com.apicatalog.parser.express;

import com.apicatalog.model.*;
import com.apicatalog.parser.ParserPlugin;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

@Component
public class ExpressParser implements ParserPlugin {

    private static final Pattern ROUTE = Pattern.compile(
        "^\\s*(?:[\\w$]+\\.)?(get|post|put|delete|patch)\\s*\\(\\s*['\"`]([^'\"`]+)['\"`]",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern HANDLER_ARG = Pattern.compile(",\\s*([A-Za-z_$][\\w$]*)\\s*[,)]");
    private static final Pattern REQ_PARAM = Pattern.compile("req\\.params(?:\\.(\\w+)|\\[['\"]([\\w]+)['\"]\\])");
    private static final Pattern REQ_QUERY = Pattern.compile("req\\.query(?:\\.(\\w+)|\\[['\"]([\\w]+)['\"]\\])");
    private static final Pattern BODY_DESTRUCT = Pattern.compile("(?:const|let|var)\\s*\\{([^}]+)\\}\\s*=\\s*req\\.body");
    private static final Pattern RES_STATUS = Pattern.compile("res\\.(?:status|sendStatus)\\s*\\(\\s*(\\d{3})\\s*\\)");
    private static final Pattern URL_PARAM = Pattern.compile(":(\\w+)");
    private static final Pattern JSDOC_LINE = Pattern.compile("^\\s*\\*\\s*(?!@)(\\w.+)$");

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
        for (int i = 0; i < lines.length; i++) {
            Matcher m = ROUTE.matcher(lines[i]);
            if (!m.find()) continue;
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
            ExtractedApi api = new ExtractedApi();
            api.setMethod(httpMethod); api.setPath(path); api.setHandler(handler); api.setDescription(desc);
            api.setParameters(params.isEmpty() ? null : params);
            api.setRequestBodyType(reqBodyType); api.setRequestBodyFields(reqBodyFields.isEmpty() ? null : reqBodyFields);
            api.setStatusCodes(codes.isEmpty() ? null : codes);
            api.setSourceFile(relPath); api.setSourceLine(i + 1);
            apis.add(api);
        }
        return apis;
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