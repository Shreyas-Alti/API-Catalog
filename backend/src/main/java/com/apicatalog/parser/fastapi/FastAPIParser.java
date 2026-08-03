package com.apicatalog.parser.fastapi;

import com.apicatalog.model.*;
import com.apicatalog.parser.ParserPlugin;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * Regex-based FastAPI parser. Used as a fallback when tree-sitter is unavailable.
 */
@Component
@Order(10)  // Lower priority — TreeSitterFastAPIParser (Order 0) takes precedence
public class FastAPIParser implements ParserPlugin {

    private static final Pattern DECORATOR = Pattern.compile(
        "^@[\\w.]+\\.(get|post|put|delete|patch)\\s*\\(([^)]*?)\\)",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    // Fast pre-check: line opens an HTTP-method decorator — may or may not close on the same line
    private static final Pattern DEC_START = Pattern.compile(
        "^@[\\w.]+\\.(get|post|put|delete|patch)\\s*\\(",
        Pattern.CASE_INSENSITIVE);
    // Anchored to start of args — captures empty string ("") as a valid path
    private static final Pattern DEC_PATH = Pattern.compile("^\\s*['\"]([^'\"]*)['\"]\\s*(?:[,)]|$)");
    private static final Pattern STATUS_CODE_ARG = Pattern.compile("status_code\\s*=\\s*(\\d+)");
    private static final Pattern RESPONSE_MODEL = Pattern.compile("response_model\\s*=\\s*(\\w+)");
    private static final Pattern FUNC_DEF = Pattern.compile("^(?:async\\s+)?def\\s+(\\w+)\\s*\\(");
    private static final Pattern RETURN_TYPE = Pattern.compile("\\)\\s*->\\s*([\\w\\[\\],\\s|]+?)\\s*:");
    // Accept any class with any (or no) base class — resolveModel verifies by exact name
    private static final Pattern PYDANTIC_CLASS = Pattern.compile(
        "^class\\s+(\\w+)\\s*(?:\\([^)]*\\))?\\s*:");
    private static final Pattern PY_FIELD = Pattern.compile(
        "^    (\\w+)\\s*:\\s*([\\w\\[\\],\\s|]+?)(?:\\s*=.*)?$");
    private static final Pattern ROUTER_TAGS = Pattern.compile(
        "APIRouter\\s*\\([^)]*tags\\s*=\\s*\\[([^\\]]*?)\\]");
    private static final Pattern LOCAL_PREFIX = Pattern.compile(
        "APIRouter\\s*\\([^)]*prefix\\s*=\\s*['\"]([^'\"]*)['\"]");
    private static final Pattern INCLUDE_ROUTER = Pattern.compile(
        "include_router\\s*\\(\\s*([\\w.]+)\\s*,[^)]*?prefix\\s*=\\s*['\"]([^'\"]*)['\"]\\s*[,)]",
        Pattern.DOTALL);

    private static final Set<String> PRIMITIVES = Set.of(
        "int","str","float","bool","bytes","None","Any","Dict","List","Tuple",
        "Optional","Union","Type","UUID","UUID4","datetime","date","time",
        "Decimal","condecimal","PositiveInt","PositiveFloat","constr",
        "EmailStr","HttpUrl","AnyUrl","SecretStr",
        "Response","JSONResponse","StreamingResponse","FileResponse",
        "HTMLResponse","RedirectResponse","PlainTextResponse","BackgroundTasks",
        "Request","HTTPException");

    @Override public String getFrameworkName() { return "FastAPI"; }
    @Override public boolean supports(Path root) { return hasDep(root, "fastapi"); }

    @Override
    public List<ExtractedApi> extract(Path root) {
        Map<String, Path> modelIndex = buildModelIndex(root);
        Map<String, String> externalPrefixMap = buildExternalPrefixMap(root);
        List<ExtractedApi> apis = new ArrayList<>();
        try {
            Files.walk(root)
                .filter(p -> p.toString().endsWith(".py"))
                .filter(p -> !p.getFileName().toString().startsWith("test_") && !p.toString().contains("/tests/"))
                .forEach(f -> { try { apis.addAll(parseFile(f, modelIndex, externalPrefixMap, root)); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
        return apis;
    }

    private Map<String, Path> buildModelIndex(Path root) {
        Map<String, Path> idx = new HashMap<>();
        try {
            Files.walk(root).filter(p -> p.toString().endsWith(".py")).forEach(f -> {
                try { for (String line : Files.readAllLines(f)) { Matcher m = PYDANTIC_CLASS.matcher(line); if (m.find()) idx.putIfAbsent(m.group(1), f); } } catch (Exception ignored) {}
            });
        } catch (IOException ignored) {}
        return idx;
    }

    private List<ExtractedApi> parseFile(Path file, Map<String, Path> modelIndex,
                                          Map<String, String> externalPrefixMap, Path root) throws IOException {
        String[] lines = Files.readString(file).split("\\r?\\n");
        String fileContent = String.join("\n", lines);
        List<ExtractedApi> apis = new ArrayList<>();
        String relPath = root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
        String controller = deriveController(file);
        List<String> routerTags = extractRouterTags(fileContent);
        String pathPrefix = normalizePathPrefix(resolveExternalPrefix(file, externalPrefixMap) + findLocalPrefix(fileContent));
        for (int i = 0; i < lines.length; i++) {
            // Quick pre-check on the raw line before doing heavier work
            if (!DEC_START.matcher(lines[i].trim()).find()) continue;
            // Collect the full decorator — it may span multiple lines until parens balance
            String decoratorText = collectDecorator(lines, i);
            Matcher dm = DECORATOR.matcher(decoratorText);
            if (!dm.find()) continue;
            String httpMethod = dm.group(1).toUpperCase();
            String decoratorArgs = dm.group(2);
            Matcher pathM = DEC_PATH.matcher(decoratorArgs);
            // No quoted first arg means path comes entirely from the prefix (e.g. @router.get(response_model=...))
            String routePath = pathM.find() ? pathM.group(1) : "";
            if (!routePath.isEmpty() && !routePath.startsWith("/")) continue; // decorator arg mistaken for path
            String path = routePath.isEmpty() ? pathPrefix : pathPrefix + routePath;
            if (path.isEmpty()) path = "/";
            List<Integer> codes = new ArrayList<>();
            Matcher scm = STATUS_CODE_ARG.matcher(decoratorArgs);
            if (scm.find()) { try { codes.add(Integer.parseInt(scm.group(1))); } catch (Exception ignored) {} }
            String responseModel = null;
            Matcher rmm = RESPONSE_MODEL.matcher(decoratorArgs);
            if (rmm.find()) responseModel = rmm.group(1);
            String handlerName = null; String funcSig = null; int funcLine = -1;
            for (int j = i + 1; j < Math.min(i + 10, lines.length); j++) {
                String c = lines[j].trim();
                if (c.startsWith("@")) continue;
                Matcher fm = FUNC_DEF.matcher(c);
                if (fm.find()) { handlerName = fm.group(1); funcSig = collectSig(lines, j); funcLine = j; break; }
            }
            if (handlerName == null) continue;
            String desc = null;
            if (funcLine >= 0) {
                for (int j = funcLine + 1; j < Math.min(funcLine + 4, lines.length); j++) {
                    String dl = lines[j].trim();
                    if (dl.startsWith("\"\"\"")) { String ds = dl.replace("\"\"\"", "").trim(); if (!ds.isEmpty()) desc = ds; break; }
                    if (!dl.isEmpty()) break;
                }
            }
            String returnType = responseModel;
            if (returnType == null && funcSig != null) {
                Matcher rtm = RETURN_TYPE.matcher(funcSig);
                if (rtm.find()) { String rt = rtm.group(1).trim(); Matcher om = Pattern.compile("(?:Optional|List|Set)\\[([\\w]+)\\]").matcher(rt); if (om.find()) rt = om.group(1); if (!PRIMITIVES.contains(rt) && !rt.equals("None")) returnType = rt; }
            }
            List<ApiParameter> params = funcSig != null ? parsePyParams(funcSig, path, modelIndex) : List.of();
            String reqBodyType = null; List<ApiField> reqBodyFields = List.of();
            for (ApiParameter p : params) { if ("BODY".equals(p.getLocation())) { reqBodyType = p.getType(); reqBodyFields = resolveModel(reqBodyType, modelIndex); break; } }
            List<ApiField> respFields = resolveModel(returnType, modelIndex);
            ExtractedApi api = new ExtractedApi();
            api.setMethod(httpMethod); api.setPath(path); api.setController(controller); api.setHandler(handlerName); api.setDescription(desc);
            // Route-level tags from decorator override router-level default
            List<String> routeTags = parseTagList(decoratorArgs);
            api.setTags(routeTags.isEmpty() ? (routerTags.isEmpty() ? null : routerTags) : routeTags);
            api.setParameters(params.isEmpty() ? null : params);
            api.setRequestBodyType(reqBodyType); api.setRequestBodyFields(reqBodyFields.isEmpty() ? null : reqBodyFields);
            api.setResponseBodyType(returnType); api.setResponseBodyFields(respFields.isEmpty() ? null : respFields);
            api.setStatusCodes(codes.isEmpty() ? null : codes);
            api.setSourceFile(relPath); api.setSourceLine(i + 1);
            ensurePathParamsPresent(api);
            apis.add(api);
        }
        return apis;
    }

    /** "user_router.py" → "UserRouter" */
    private String deriveController(Path file) {
        String name = file.getFileName().toString().replaceAll("\\.py$", "");
        return Arrays.stream(name.split("[-_.]"))
                .filter(w -> !w.isEmpty())
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1))
                .collect(Collectors.joining());
    }

    /** Extract router-level default tags from APIRouter(tags=["articles"]) in the file. */
    private List<String> extractRouterTags(String fileContent) {
        Matcher m = ROUTER_TAGS.matcher(fileContent);
        return m.find() ? parseTagList(m.group(1)) : List.of();
    }

    /** Parse a Python list of string literals: ["articles", 'users'] → [articles, users]. */
    private List<String> parseTagList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> tags = new ArrayList<>();
        Matcher m = Pattern.compile("['\"]([^'\"]+)['\"]").matcher(raw);
        while (m.find()) tags.add(m.group(1));
        return tags;
    }

    /**
     * Joins lines starting at {@code start} until the outermost parentheses opened
     * on the first line are closed.  Handles decorators like:
     * <pre>
     *   @router.get(
     *       "/users/{id}",
     *       response_model=UserOut,
     *   )
     * </pre>
     * Returns a single space-joined string safe for DECORATOR to match against.
     */
    private String collectDecorator(String[] lines, int start) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        boolean opened = false;
        for (int i = start; i < Math.min(start + 15, lines.length); i++) {
            String l = lines[i].trim();
            if (i > start) sb.append(' ');
            sb.append(l);
            for (char c : l.toCharArray()) {
                if (c == '(') { opened = true; depth++; }
                else if (c == ')') { depth--; if (opened && depth == 0) return sb.toString(); }
            }
        }
        return sb.toString();
    }

    private String collectSig(String[] lines, int start) {
        StringBuilder sb = new StringBuilder(); int d = 0; boolean open = false;
        for (int i = start; i < Math.min(start + 20, lines.length); i++) {
            String l = lines[i].trim(); sb.append(" ").append(l);
            for (char c : l.toCharArray()) { if (c == '(') { open = true; d++; } else if (c == ')') { d--; if (open && d == 0) return sb.toString().trim(); } }
        }
        return sb.toString().trim();
    }

    private List<ApiParameter> parsePyParams(String sig, String urlPath, Map<String, Path> modelIndex) {
        int s = sig.indexOf('('); if (s < 0) return List.of();
        int d = 0, end = -1;
        for (int i = s; i < sig.length(); i++) { char c = sig.charAt(i); if (c == '(') d++; else if (c == ')') { d--; if (d == 0) { end = i; break; } } }
        if (end < 0) return List.of();
        Set<String> pathParams = new HashSet<>();
        Matcher um = Pattern.compile("\\{([^}]+)\\}").matcher(urlPath);
        while (um.find()) pathParams.add(um.group(1));
        List<ApiParameter> result = new ArrayList<>();
        for (String raw : splitComma(sig.substring(s + 1, end))) {
            raw = raw.trim();
            if (raw.isEmpty() || raw.equals("self") || raw.equals("*") || raw.startsWith("**")) continue;
            Matcher pm = Pattern.compile("^(\\w+)\\s*:\\s*([^=]+?)(?:\\s*=\\s*(.+))?$").matcher(raw);
            if (!pm.find()) continue;
            String name = pm.group(1); String typeAnn = pm.group(2).trim(); String defVal = pm.group(3) != null ? pm.group(3).trim() : null;
            if (name.equals("db") || name.equals("session") || name.equals("background_tasks")) continue;
            String cleanType = typeAnn;
            Matcher optm = Pattern.compile("Optional\\[([^\\]]+)\\]").matcher(typeAnn);
            if (optm.find()) cleanType = optm.group(1).trim();
            boolean required = !typeAnn.contains("Optional") && defVal == null;
            String loc;
            if (defVal != null && defVal.startsWith("Header(")) loc = "HEADER";
            else if (defVal != null && defVal.startsWith("Body(")) loc = "BODY";
            else if (pathParams.contains(name)) loc = "PATH";
            else if (!PRIMITIVES.contains(cleanType) && modelIndex.containsKey(cleanType)) loc = "BODY";
            else if (isPrimitive(cleanType)) loc = "QUERY";
            else continue;
            ApiParameter p = new ApiParameter(); p.setName(name); p.setType(cleanType); p.setLocation(loc); p.setRequired(required); result.add(p);
        }
        return result;
    }

    private boolean isPrimitive(String t) { return PRIMITIVES.contains(t) || t.startsWith("List[") || t.startsWith("Optional[") || t.startsWith("Dict["); }

    private List<ApiField> resolveModel(String name, Map<String, Path> modelIndex) {
        if (name == null || name.isBlank() || PRIMITIVES.contains(name)) return List.of();
        Path f = modelIndex.get(name); if (f == null) return List.of();
        List<ApiField> fields = new ArrayList<>();
        try {
            String[] lines = Files.readString(f).split("\\r?\\n");
            boolean inClass = false;
            for (String rawLine : lines) {
                String t = rawLine.trim();
                if (t.startsWith("class " + name + "(") || t.equals("class " + name + ":")) { inClass = true; continue; }
                if (inClass) {
                    if (!t.isEmpty() && !rawLine.startsWith("    ") && !rawLine.startsWith("\t") && !t.startsWith("#")) { inClass = false; continue; }
                    if (t.startsWith("@") || t.startsWith("#") || t.startsWith("def ") || t.startsWith("class ")) continue;
                    Matcher fm = PY_FIELD.matcher(rawLine);
                    if (fm.find()) { String type = fm.group(2).trim(); Matcher om = Pattern.compile("Optional\\[([^\\]]+)\\]").matcher(type); if (om.find()) type = om.group(1).trim(); ApiField field = new ApiField(); field.setName(fm.group(1)); field.setType(type); fields.add(field); }
                }
            }
        } catch (Exception ignored) {}
        return fields;
    }

    private List<String> splitComma(String s) {
        List<String> r = new ArrayList<>(); int d = 0; StringBuilder cur = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '[' || c == '(' || c == '{') d++; else if (c == ']' || c == ')' || c == '}') d--;
            else if (c == ',' && d == 0) { if (!cur.toString().isBlank()) r.add(cur.toString()); cur = new StringBuilder(); continue; }
            cur.append(c);
        }
        if (!cur.toString().isBlank()) r.add(cur.toString());
        return r;
    }

    private boolean hasDep(Path root, String dep) {
        for (String f : List.of("requirements.txt", "pyproject.toml", "Pipfile", "setup.py")) {
            Path c = root.resolve(f); if (Files.exists(c)) { try { if (Files.readString(c).toLowerCase().contains(dep)) return true; } catch (IOException ignored) {} }
        }
        return false;
    }

    /** Scan the whole repo for include_router(..., prefix="...") and build a module→prefix map. */
    private Map<String, String> buildExternalPrefixMap(Path root) {
        Map<String, String> map = new HashMap<>();
        try {
            Files.walk(root)
                .filter(p -> p.toString().endsWith(".py"))
                .forEach(f -> {
                    try {
                        Matcher m = INCLUDE_ROUTER.matcher(Files.readString(f));
                        while (m.find()) map.putIfAbsent(m.group(1), m.group(2));
                    } catch (IOException ignored) {}
                });
        } catch (IOException ignored) {}
        return map;
    }

    /** Extract the local router prefix from APIRouter(prefix="...") in the file. */
    private String findLocalPrefix(String fileContent) {
        Matcher m = LOCAL_PREFIX.matcher(fileContent);
        return m.find() ? m.group(1) : "";
    }

    /**
     * Match the file's module name against the externalPrefixMap.
     * Handles the common convention: "users.py" referenced as "users.router".
     */
    private String resolveExternalPrefix(Path file, Map<String, String> externalPrefixMap) {
        String mod = file.getFileName().toString().replace(".py", "");
        return externalPrefixMap.entrySet().stream()
                .filter(e -> e.getKey().equals(mod) || e.getKey().startsWith(mod + "."))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
    }

    /** Ensure prefix starts with '/' if non-empty and has no trailing slash. */
    private String normalizePathPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) return "";
        String s = prefix.startsWith("/") ? prefix : "/" + prefix;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * Inject PATH parameters for any {token} in the path that has no declared parameter.
     * Handles Depends-injected path params whose type the function-signature scanner can't trace.
     */
    private void ensurePathParamsPresent(ExtractedApi api) {
        if (api.getPath() == null) return;
        List<ApiParameter> existing = api.getParameters();
        Set<String> declared = new HashSet<>();
        if (existing != null) existing.forEach(p -> declared.add(p.getName()));
        List<ApiParameter> toAdd = new ArrayList<>();
        Matcher m = Pattern.compile("\\{(\\w+)\\}").matcher(api.getPath());
        while (m.find()) {
            String name = m.group(1);
            if (declared.add(name)) {
                ApiParameter p = new ApiParameter();
                p.setName(name); p.setLocation("PATH"); p.setRequired(true); p.setType("string");
                toAdd.add(p);
            }
        }
        if (!toAdd.isEmpty()) {
            List<ApiParameter> merged = new ArrayList<>(toAdd); // path params first
            if (existing != null) merged.addAll(existing);
            api.setParameters(merged);
        }
    }
}