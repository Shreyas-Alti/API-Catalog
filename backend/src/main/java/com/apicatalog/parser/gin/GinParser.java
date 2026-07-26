package com.apicatalog.parser.gin;

import com.apicatalog.model.*;
import com.apicatalog.parser.ParserPlugin;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

@Component
public class GinParser implements ParserPlugin {

    private static final Pattern ROUTE = Pattern.compile(
        "^\\s*[\\w]+\\.(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\s*\\(\\s*\"([^\"]+)\"");
    // ^^^ Handler no longer captured here — we resolve it from the full balanced call.
    private static final Pattern FUNC_DEF = Pattern.compile("^func\\s+(\\w+)\\s*\\(");
    private static final Pattern GIN_PARAM = Pattern.compile("c\\.Param\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern GIN_QUERY = Pattern.compile("c\\.(?:Query|DefaultQuery|GetQuery)\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern GIN_HEADER = Pattern.compile("c\\.GetHeader\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern GIN_BIND = Pattern.compile("c\\.(?:ShouldBind(?:JSON|XML|Query|Form)?|Bind(?:JSON|XML|Form)?)\\s*\\(\\s*&?(\\w+)");
    private static final Pattern VAR_TYPE = Pattern.compile("(?:var\\s+(\\w+)\\s+(\\w+)|(\\w+)\\s*:=\\s*(?:new\\s*)?(\\w+)\\s*\\{)");
    private static final Pattern GIN_JSON = Pattern.compile("c\\.(?:JSON|IndentedJSON|Status|AbortWithStatus)\\s*\\(\\s*(?:http\\.(\\w+)|(\\d+))");
    private static final Pattern STRUCT_FIELD = Pattern.compile("^\\s+(\\w+)\\s+([\\w*\\[\\]]+)(?:\\s+`[^`]*json:\\s*\"([^,\"]+)[^\"]*\"[^`]*`)?\\s*$");
    private static final Pattern GO_COMMENT = Pattern.compile("^//\\s*(.+)$");

    @Override public String getFrameworkName() { return "Gin"; }

    @Override
    public boolean supports(Path root) {
        Path goMod = root.resolve("go.mod");
        if (!Files.exists(goMod)) return false;
        try { return Files.readString(goMod).contains("gin-gonic/gin"); }
        catch (IOException e) { return false; }
    }

    @Override
    public List<ExtractedApi> extract(Path root) {
        Map<String, Path> structIndex = buildStructIndex(root);
        List<ExtractedApi> apis = new ArrayList<>();
        try {
            Files.walk(root)
                .filter(p -> p.toString().endsWith(".go"))
                .filter(p -> !p.toString().contains("vendor") && !p.getFileName().toString().endsWith("_test.go"))
                .forEach(f -> { try { apis.addAll(parseFile(f, structIndex, root)); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
        return apis;
    }

    private Map<String, Path> buildStructIndex(Path root) {
        Map<String, Path> idx = new HashMap<>();
        try {
            Files.walk(root).filter(p -> p.toString().endsWith(".go") && !p.toString().contains("vendor")).forEach(f -> {
                try { for (String line : Files.readAllLines(f)) { Matcher m = Pattern.compile("^type\\s+(\\w+)\\s+struct").matcher(line.trim()); if (m.find()) idx.putIfAbsent(m.group(1), f); } } catch (Exception ignored) {}
            });
        } catch (IOException ignored) {}
        return idx;
    }

    private List<ExtractedApi> parseFile(Path file, Map<String, Path> structIndex, Path root) throws IOException {
        String[] lines = Files.readAllLines(file).toArray(new String[0]);
        List<ExtractedApi> apis = new ArrayList<>();
        String relPath = root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
        Map<String, Integer> funcLines = new HashMap<>();
        for (int i = 0; i < lines.length; i++) { Matcher fm = FUNC_DEF.matcher(lines[i]); if (fm.find()) funcLines.put(fm.group(1), i); }
        for (int i = 0; i < lines.length; i++) {
            Matcher m = ROUTE.matcher(lines[i]);
            if (!m.find()) continue;
            String httpMethod = m.group(1);
            String path = m.group(2);
            // Collect the full paren-balanced call to find the last identifier
            // (handles multi-line gofmt-wrapped calls and multi-middleware routes)
            String handlerRef = resolveLastArg(lines, i);
            String handler = handlerRef;
            if (handler != null && handler.contains(".")) handler = handler.substring(handler.lastIndexOf('.') + 1);
            List<ApiParameter> params = new ArrayList<>();
            Set<String> seenPath = new HashSet<>();
            Matcher urlPm = Pattern.compile(":(\\w+)").matcher(path);
            while (urlPm.find()) { String pn = urlPm.group(1); seenPath.add(pn); ApiParameter p = new ApiParameter(); p.setName(pn); p.setType("string"); p.setLocation("PATH"); p.setRequired(true); params.add(p); }
            String desc = null; String reqBodyType = null; List<ApiField> reqBodyFields = List.of();
            List<Integer> codes = new ArrayList<>();
            Set<String> queryNames = new LinkedHashSet<>(); Set<String> headerNames = new LinkedHashSet<>();
            Integer funcStart = funcLines.get(handler);
            if (funcStart != null) {
                for (int k = funcStart - 1; k >= Math.max(0, funcStart - 3); k--) { Matcher cm = GO_COMMENT.matcher(lines[k].trim()); if (cm.find()) { desc = cm.group(1).trim(); break; } if (!lines[k].trim().isEmpty()) break; }
                Map<String, String> varTypes = new HashMap<>();
                for (int k = funcStart + 1; k < Math.min(funcStart + 80, lines.length); k++) {
                    String bl = lines[k];
                    if (bl.trim().equals("}") && braceDepth(lines, funcStart, k) == 0) break;
                    Matcher vtm = VAR_TYPE.matcher(bl); if (vtm.find()) { if (vtm.group(1) != null) varTypes.put(vtm.group(1), vtm.group(2)); else if (vtm.group(3) != null) varTypes.put(vtm.group(3), vtm.group(4)); }
                    Matcher prm = GIN_PARAM.matcher(bl); while (prm.find()) { String pn = prm.group(1); if (!seenPath.contains(pn)) { ApiParameter p = new ApiParameter(); p.setName(pn); p.setType("string"); p.setLocation("PATH"); p.setRequired(true); params.add(p); seenPath.add(pn); } }
                    Matcher qm = GIN_QUERY.matcher(bl); while (qm.find()) queryNames.add(qm.group(1));
                    Matcher hm = GIN_HEADER.matcher(bl); while (hm.find()) headerNames.add(hm.group(1));
                    Matcher bm = GIN_BIND.matcher(bl);
                    if (bm.find()) { String bv = bm.group(1); String st = varTypes.get(bv); if (st != null) { reqBodyType = st; reqBodyFields = resolveStruct(st, structIndex); } else if (reqBodyType == null) reqBodyType = bv; }
                    Matcher jm = GIN_JSON.matcher(bl); if (jm.find()) { int code = jm.group(1) != null ? goStatusToCode(jm.group(1)) : parseInt(jm.group(2)); if (code > 0 && !codes.contains(code)) codes.add(code); }
                }
            }
            for (String qn : queryNames) { ApiParameter p = new ApiParameter(); p.setName(qn); p.setType("string"); p.setLocation("QUERY"); p.setRequired(false); params.add(p); }
            for (String hn : headerNames) { ApiParameter p = new ApiParameter(); p.setName(hn); p.setType("string"); p.setLocation("HEADER"); p.setRequired(false); params.add(p); }
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

    /**
     * Collect the parenthesised argument list of a router.METHOD(...) call,
     * walking forward across line breaks until parens balance, then return
     * the last identifier — which is the handler (possibly after middleware).
     *
     * Identifiers are captured per-line from whatever portion precedes the
     * closing paren, so single-line, multi-line, and last-arg-shares-closing-paren
     * cases all resolve correctly.
     */
    private String resolveLastArg(String[] lines, int start) {
        int depth = 0; boolean started = false;
        String lastIdent = null;
        for (int i = start; i < Math.min(start + 20, lines.length); i++) {
            for (int ci = 0; ci < lines[i].length(); ci++) {
                char c = lines[i].charAt(ci);
                if (c == '(') { started = true; depth++; }
                else if (c == ')') {
                    depth--;
                    if (started && depth == 0) {
                        // Scan identifiers in the substring UP TO (not including) this ')'
                        // so that args sharing the closing-paren line are captured.
                        Matcher idm = Pattern.compile("\\b([A-Za-z_]\\w*)\\b")
                                .matcher(lines[i].substring(0, ci));
                        while (idm.find()) {
                            String id = idm.group(1);
                            if (!id.equals("nil") && !id.equals("true") && !id.equals("false")
                                    && !id.equals("err") && !id.equals("ctx")) lastIdent = id;
                        }
                        return lastIdent;
                    }
                }
            }
            // Extract identifiers from lines that are fully inside the call
            // (i.e., we didn't hit depth==0 above, so this line had no closing paren)
            if (started && depth >= 1) {
                Matcher idm = Pattern.compile("\\b([A-Za-z_]\\w*)\\b").matcher(lines[i]);
                while (idm.find()) {
                    String id = idm.group(1);
                    if (!id.equals("nil") && !id.equals("true") && !id.equals("false")
                            && !id.equals("err") && !id.equals("ctx")) lastIdent = id;
                }
            }
        }
        return lastIdent;
    }

    private List<ApiField> resolveStruct(String structName, Map<String, Path> structIndex) {
        Path f = structIndex.get(structName); if (f == null) return List.of();
        List<ApiField> fields = new ArrayList<>();
        try {
            String[] lines = Files.readAllLines(f).toArray(new String[0]);
            boolean in = false;
            for (String rawLine : lines) {
                String t = rawLine.trim();
                if (Pattern.compile("^type\\s+" + structName + "\\s+struct").matcher(t).find()) { in = true; continue; }
                if (in) { if (t.equals("}")) break; Matcher fm = STRUCT_FIELD.matcher(rawLine); if (fm.find() && !fm.group(1).isEmpty() && Character.isUpperCase(fm.group(1).charAt(0))) { ApiField field = new ApiField(); String jsonName = fm.group(3) != null ? fm.group(3) : fm.group(1); field.setName(jsonName); field.setType(fm.group(2)); fields.add(field); } }
            }
        } catch (Exception ignored) {}
        return fields;
    }

    private int braceDepth(String[] lines, int start, int cur) {
        int d = 0; for (int i = start; i <= cur; i++) for (char c : lines[i].toCharArray()) { if (c == '{') d++; else if (c == '}') d--; } return d;
    }

    private int goStatusToCode(String name) {
        return switch (name) {
            case "StatusOK" -> 200; case "StatusCreated" -> 201; case "StatusAccepted" -> 202;
            case "StatusNoContent" -> 204; case "StatusBadRequest" -> 400; case "StatusUnauthorized" -> 401;
            case "StatusForbidden" -> 403; case "StatusNotFound" -> 404; case "StatusConflict" -> 409;
            case "StatusUnprocessableEntity" -> 422; case "StatusInternalServerError" -> 500;
            default -> 0;
        };
    }

    private int parseInt(String s) { if (s == null) return 0; try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; } }
}