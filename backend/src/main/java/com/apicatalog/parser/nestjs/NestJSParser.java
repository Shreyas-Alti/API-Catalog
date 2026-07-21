package com.apicatalog.parser.nestjs;

import com.apicatalog.model.*;
import com.apicatalog.parser.ParserPlugin;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

@Component
public class NestJSParser implements ParserPlugin {

    private static final Pattern HTTP_DEC = Pattern.compile(
        "@(Get|Post|Put|Delete|Patch|Head|Options)\\s*(?:\\(\\s*['\"`]([^'\"`]*)['\"`]\\s*\\))?",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTROLLER = Pattern.compile(
        "@Controller\\s*(?:\\(\\s*['\"`]([^'\"`]*)['\"`]\\s*\\))?");
    private static final Pattern CLASS_NAME = Pattern.compile(
        "(?:export\\s+)?(?:abstract\\s+)?class\\s+(\\w+)");
    private static final Pattern METHOD_SIG = Pattern.compile(
        "(?:(?:public|private|protected|async)\\s+)*(\\w+)\\s*\\(");
    private static final Pattern API_OPERATION = Pattern.compile(
        "@ApiOperation\\s*\\(\\s*\\{[^}]*summary\\s*:\\s*['\"`]([^'\"`]+)['\"`]");
    private static final Pattern API_TAGS = Pattern.compile("@ApiTags\\s*\\(([^)]+)\\)");
    private static final Pattern HTTP_CODE = Pattern.compile("@HttpCode\\s*\\(\\s*(\\d+)\\s*\\)");
    private static final Pattern API_RESPONSE = Pattern.compile(
        "@ApiResponse\\s*\\(\\s*\\{[^}]*status\\s*:\\s*(\\d+)");
    private static final Pattern PARAM_DEC = Pattern.compile(
        "@(Param|Query|Body|Headers|Header|Req|Res)\\s*(?:\\(\\s*['\"`]([^'\"`]*)['\"`]\\s*\\))?");
    private static final Pattern TS_VALIDATOR = Pattern.compile(
        "@(IsNotEmpty|IsString|IsNumber|IsEmail|IsOptional|IsArray|IsBoolean|MinLength|MaxLength|Min|Max|IsEnum|IsUUID|IsDate|IsInt|IsDecimal|Length|Matches|ArrayNotEmpty|ValidateNested)(?:\\(([^)]*)\\))?");
    private static final Pattern TS_FIELD = Pattern.compile(
        "^\\s*(\\w+)\\s*\\??:\\s*([\\w<>\\[\\]|\\s]+?)\\s*[;,]");

    private static final Set<String> PRIMITIVE_TYPES = Set.of(
        "void","string","number","boolean","any","unknown","never","null",
        "undefined","object","String","Number","Boolean","Promise",
        "Observable","Response","Request","HttpStatus");

    @Override public String getFrameworkName() { return "NestJS"; }

    @Override
    public boolean supports(Path root) {
        Path pkg = root.resolve("package.json");
        if (!Files.exists(pkg)) return false;
        try { return Files.readString(pkg).contains("\"@nestjs/core\""); }
        catch (IOException e) { return false; }
    }

    @Override
    public List<ExtractedApi> extract(Path root) {
        Map<String, Path> typeIndex = buildTypeIndex(root);
        List<ExtractedApi> apis = new ArrayList<>();
        try {
            Files.walk(root)
                .filter(p -> p.toString().endsWith(".ts"))
                .filter(p -> !p.toString().contains("node_modules"))
                .filter(p -> !p.toString().contains(".spec.") && !p.toString().contains(".test."))
                .forEach(f -> { try { apis.addAll(parseFile(f, typeIndex, root)); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
        return apis;
    }

    private Map<String, Path> buildTypeIndex(Path root) {
        Map<String, Path> index = new HashMap<>();
        try {
            Files.walk(root)
                .filter(p -> p.toString().endsWith(".ts") && !p.toString().contains("node_modules"))
                .forEach(f -> {
                    try { Matcher m = Pattern.compile("(?:export\\s+)?(?:class|interface|enum)\\s+(\\w+)").matcher(Files.readString(f)); while (m.find()) index.putIfAbsent(m.group(1), f); } catch (Exception ignored) {}
                });
        } catch (IOException ignored) {}
        return index;
    }

    private List<ExtractedApi> parseFile(Path file, Map<String, Path> typeIndex, Path root) throws IOException {
        String content = Files.readString(file);
        if (!content.contains("@Controller")) return Collections.emptyList();
        String[] lines = content.split("\\r?\\n");
        String className = firstMatch(CLASS_NAME, content, 1);
        String basePath = ""; Matcher ctm = CONTROLLER.matcher(content); if (ctm.find()) basePath = ctm.group(1);
        List<String> classTags = new ArrayList<>();
        Matcher clsTagM = API_TAGS.matcher(content);
        if (clsTagM.find()) { Matcher qm = Pattern.compile("['\"`]([^'\"`]+)['\"`]").matcher(clsTagM.group(1)); while (qm.find()) classTags.add(qm.group(1)); }
        String relPath = root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
        List<ExtractedApi> apis = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("@")) { pending.add(line); continue; }
            if (line.isEmpty()) continue;
            if (!pending.isEmpty()) {
                String httpMethod = null; String methodPath = null;
                for (String ann : pending) { Matcher hm = HTTP_DEC.matcher(ann); if (hm.find()) { httpMethod = hm.group(1).toUpperCase(); methodPath = hm.group(2) != null ? hm.group(2) : ""; break; } }
                if (httpMethod != null) {
                    String sig = collectSig(lines, i);
                    String handlerName = null; Matcher mm = METHOD_SIG.matcher(sig != null ? sig : line); if (mm.find()) handlerName = mm.group(1);
                    String desc = null; for (String ann : pending) { Matcher om = API_OPERATION.matcher(ann); if (om.find()) { desc = om.group(1); break; } }
                    List<Integer> codes = new ArrayList<>();
                    for (String ann : pending) {
                        Matcher hcm = HTTP_CODE.matcher(ann); if (hcm.find()) { try { codes.add(Integer.parseInt(hcm.group(1))); } catch (Exception ignored) {} }
                        Matcher arm = API_RESPONSE.matcher(ann); if (arm.find()) { try { codes.add(Integer.parseInt(arm.group(1))); } catch (Exception ignored) {} }
                    }
                    List<String> tags = new ArrayList<>(classTags);
                    for (String ann : pending) { Matcher tm = API_TAGS.matcher(ann); if (tm.find()) { Matcher qm = Pattern.compile("['\"`]([^'\"`]+)['\"`]").matcher(tm.group(1)); while (qm.find()) tags.add(qm.group(1)); } }
                    List<ApiParameter> params = sig != null ? parseMethodParams(sig) : List.of();
                    String reqBodyType = null; List<ApiField> reqBodyFields = List.of();
                    for (ApiParameter p : params) { if ("BODY".equals(p.getLocation())) { reqBodyType = p.getType(); reqBodyFields = resolveType(reqBodyType, typeIndex); break; } }
                    String returnType = sig != null ? extractReturnType(sig) : null;
                    List<ApiField> respFields = resolveType(returnType, typeIndex);
                    ExtractedApi api = new ExtractedApi();
                    api.setMethod(httpMethod); api.setPath(joinPaths(basePath, methodPath));
                    api.setController(className); api.setHandler(handlerName); api.setDescription(desc);
                    api.setTags(tags.isEmpty() ? null : tags);
                    api.setParameters(params.isEmpty() ? null : params);
                    api.setRequestBodyType(reqBodyType); api.setRequestBodyFields(reqBodyFields.isEmpty() ? null : reqBodyFields);
                    api.setResponseBodyType(returnType); api.setResponseBodyFields(respFields.isEmpty() ? null : respFields);
                    api.setStatusCodes(codes.isEmpty() ? null : codes);
                    api.setSourceFile(relPath); api.setSourceLine(i + 1);
                    apis.add(api);
                }
                pending.clear();
            } else { pending.clear(); }
        }
        return apis;
    }

    private String collectSig(String[] lines, int start) {
        StringBuilder sb = new StringBuilder(); int d = 0; boolean open = false;
        for (int i = start; i < Math.min(start + 12, lines.length); i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("/*")) continue; // skip comment lines only
            sb.append(" ").append(l);
            for (char c : l.toCharArray()) { if (c == '(') { open = true; d++; } else if (c == ')') { d--; if (open && d == 0) return sb.toString().trim(); } }
        }
        return sb.toString().trim();
    }

    private List<ApiParameter> parseMethodParams(String sig) {
        int s = sig.indexOf('('); if (s < 0) return List.of();
        int d = 0, end = -1;
        for (int i = s; i < sig.length(); i++) { char c = sig.charAt(i); if (c == '(') d++; else if (c == ')') { d--; if (d == 0) { end = i; break; } } }
        if (end < 0) return List.of();
        String paramStr = sig.substring(s + 1, end).trim(); if (paramStr.isEmpty()) return List.of();
        List<ApiParameter> result = new ArrayList<>();
        for (String raw : splitComma(paramStr)) { ApiParameter p = parseTsParam(raw.trim()); if (p != null) result.add(p); }
        return result;
    }

    private ApiParameter parseTsParam(String raw) {
        Matcher dm = PARAM_DEC.matcher(raw); if (!dm.find()) return null;
        String decName = dm.group(1); String annValue = dm.group(2);
        String loc = switch (decName) { case "Param" -> "PATH"; case "Query" -> "QUERY"; case "Body" -> "BODY"; case "Headers", "Header" -> "HEADER"; default -> null; };
        if (loc == null) return null;
        String stripped = raw.replaceAll("@\\w+(?:\\([^)]*\\))?\\s*", "").trim();
        Matcher tsm = Pattern.compile("(\\w+)\\s*\\??:\\s*([\\w<>\\[\\]|\\s]+)").matcher(stripped);
        String name = annValue; String type = null;
        if (tsm.find()) { if (name == null || name.isEmpty()) name = tsm.group(1); type = tsm.group(2).trim(); }
        List<String> vals = new ArrayList<>();
        Matcher vm = TS_VALIDATOR.matcher(raw); while (vm.find()) { String v = "@" + vm.group(1); if (vm.group(2) != null && !vm.group(2).isBlank()) v += "(" + vm.group(2).trim() + ")"; vals.add(v); }
        ApiParameter p = new ApiParameter(); p.setName(name); p.setType(type); p.setLocation(loc);
        p.setRequired(!raw.contains("?") || "BODY".equals(loc) || "PATH".equals(loc));
        p.setValidations(vals.isEmpty() ? null : vals);
        return p;
    }

    private String extractReturnType(String sig) {
        int close = -1, d = 0; boolean open = false;
        for (int i = 0; i < sig.length(); i++) { char c = sig.charAt(i); if (c == '(') { open = true; d++; } else if (c == ')') { d--; if (open && d == 0) { close = i; break; } } }
        if (close < 0) return null;
        String after = sig.substring(close + 1).trim();
        if (!after.startsWith(":")) return null;
        after = after.substring(1).trim();
        Matcher wm = Pattern.compile("^(?:Promise|Observable)\\s*<\\s*(.+?)\\s*>").matcher(after);
        if (wm.find()) after = wm.group(1).trim();
        Matcher tm = Pattern.compile("^([\\w<>\\[\\]]+)").matcher(after);
        if (!tm.find()) return null;
        String t = tm.group(1);
        return PRIMITIVE_TYPES.contains(t) || t.isEmpty() ? null : t;
    }

    private List<ApiField> resolveType(String typeName, Map<String, Path> typeIndex) {
        if (typeName == null || typeName.isBlank() || PRIMITIVE_TYPES.contains(typeName)) return List.of();
        if (typeName.endsWith("[]")) typeName = typeName.replace("[]", "").trim();
        Matcher am = Pattern.compile("Array<([\\w]+)>").matcher(typeName); if (am.find()) typeName = am.group(1);
        Path f = typeIndex.get(typeName); if (f == null) return List.of();
        List<ApiField> fields = new ArrayList<>();
        try {
            String[] lines = Files.readString(f).split("\\r?\\n");
            boolean inClass = false; List<String> pendingV = new ArrayList<>();
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.contains("class " + typeName) || line.contains("interface " + typeName)) { inClass = true; continue; }
                if (!inClass) continue;
                if (line.equals("}")) { inClass = false; continue; }
                if (line.startsWith("@")) { Matcher vm = TS_VALIDATOR.matcher(line); while (vm.find()) { String v = "@" + vm.group(1); if (vm.group(2) != null && !vm.group(2).isBlank()) v += "(" + vm.group(2).trim() + ")"; pendingV.add(v); } continue; }
                Matcher fm = TS_FIELD.matcher(rawLine);
                if (fm.find() && !fm.group(1).equals("constructor") && !line.contains("(")) { ApiField field = new ApiField(); field.setName(fm.group(1)); field.setType(fm.group(2).trim()); field.setValidations(pendingV.isEmpty() ? null : new ArrayList<>(pendingV)); fields.add(field); pendingV.clear(); }
                else if (!line.isEmpty()) { pendingV.clear(); }
            }
        } catch (Exception ignored) {}
        return fields;
    }

    private List<String> splitComma(String s) {
        List<String> r = new ArrayList<>(); int d = 0; StringBuilder cur = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '<' || c == '(' || c == '[') d++; else if (c == '>' || c == ')' || c == ']') d--;
            else if (c == ',' && d == 0) { if (!cur.toString().isBlank()) r.add(cur.toString()); cur = new StringBuilder(); continue; }
            cur.append(c);
        }
        if (!cur.toString().isBlank()) r.add(cur.toString());
        return r;
    }

    private String joinPaths(String base, String path) {
        if (base == null) base = ""; if (path == null) path = "";
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String p = path.startsWith("/") ? path : (path.isEmpty() ? "" : "/" + path);
        String j = b + p; return j.isEmpty() ? "/" : (j.startsWith("/") ? j : "/" + j);
    }

    private String firstMatch(Pattern pat, String text, int group) {
        Matcher m = pat.matcher(text); if (m.find()) { try { return m.group(group); } catch (Exception ignored) {} } return null;
    }
}