package com.apicatalog.parser.aspnet;

import com.apicatalog.model.*;
import com.apicatalog.parser.ParserPlugin;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

@Component
public class AspNetParser implements ParserPlugin {

    private static final Pattern HTTP_METHOD = Pattern.compile(
        "\\[Http(Get|Post|Put|Delete|Patch)(?:\\s*\\(\\s*\"([^\"]*)\"\\s*\\))?\\]");
    private static final Pattern ROUTE_ATTR = Pattern.compile(
        "\\[Route\\s*\\(\\s*\"([^\"]*)\"\\s*\\)\\]");
    private static final Pattern CLASS_NAME = Pattern.compile(
        "(?:public|internal)\\s+class\\s+(\\w+)(?:Controller)?");
    private static final Pattern METHOD_SIG = Pattern.compile(
        "(?:public|private|protected|internal)\\s+(?:async\\s+)?(?:Task<)?[\\w<>\\[\\]?]+>?\\s+(\\w+)\\s*\\(");

    // Parameter source attributes
    private static final Pattern FROM_ROUTE  = Pattern.compile("\\[FromRoute(?:\\(\"([^\"]+)\")?\\]?|\\[FromRoute\\]");
    private static final Pattern FROM_QUERY  = Pattern.compile("\\[FromQuery(?:\\([^)]*Name\\s*=\\s*\"([^\"]+)\")?[^)]*\\]?|\\[FromQuery\\]");
    private static final Pattern FROM_BODY   = Pattern.compile("\\[FromBody\\]");
    private static final Pattern FROM_HEADER = Pattern.compile("\\[FromHeader(?:\\([^)]*Name\\s*=\\s*\"([^\"]+)\")?[^)]*\\]?|\\[FromHeader\\]");
    private static final Pattern FROM_FORM   = Pattern.compile("\\[FromForm\\]");

    // ProducesResponseType / StatusCodes
    private static final Pattern PRODUCES_STATUS = Pattern.compile(
        "\\[(?:ProducesResponseType|Produces)\\s*\\(?\\s*(?:typeof\\([^)]+\\)\\s*,\\s*)?(\\d{3}|StatusCodes\\.Status(\\d{3}\\w+))");

    // XML doc summary: /// <summary>Description</summary>
    private static final Pattern XML_SUMMARY = Pattern.compile("<summary>\\s*([^<]+?)\\s*</summary>");

    // C# field/property for DTO resolution: public Type Name { get; set; }
    private static final Pattern CS_PROP = Pattern.compile(
        "public\\s+([\\w<>\\[\\]?]+)\\s+(\\w+)\\s*(?:\\{\\s*get;|=>)");
    // Required attribute
    private static final Pattern CS_REQUIRED = Pattern.compile("\\[Required\\]");

    private static final Set<String> NON_DTO = Set.of(
        "void","string","int","long","bool","double","float","decimal","byte","short",
        "string[]","int[]","bool?","int?","long?","Guid","DateTime","DateTimeOffset",
        "Task","IActionResult","ActionResult","OkResult","BadRequestResult","NotFoundResult",
        "IEnumerable","IList","List","IQueryable","object","dynamic","Stream",
        "IFormFile","CancellationToken","ClaimsPrincipal","HttpContext");

    @Override public String getFrameworkName() { return "ASP.NET Core"; }

    @Override
    public boolean supports(Path root) {
        try {
            return Files.walk(root, 3).filter(p -> p.toString().endsWith(".csproj"))
                .anyMatch(p -> { try { String c = Files.readString(p); return c.contains("Microsoft.AspNetCore") || c.contains("net8") || c.contains("net7") || c.contains("net6"); } catch (IOException e) { return false; } });
        } catch (IOException e) { return false; }
    }

    @Override
    public List<ExtractedApi> extract(Path root) {
        Map<String, Path> classIndex = buildClassIndex(root);
        List<ExtractedApi> apis = new ArrayList<>();
        try {
            Files.walk(root)
                .filter(p -> p.toString().endsWith(".cs"))
                .filter(p -> !p.toString().contains("obj") && !p.toString().contains("bin") && !p.getFileName().toString().contains("Test"))
                .forEach(f -> { try { apis.addAll(parseFile(f, classIndex, root)); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
        return apis;
    }

    private Map<String, Path> buildClassIndex(Path root) {
        Map<String, Path> idx = new HashMap<>();
        try {
            Files.walk(root).filter(p -> p.toString().endsWith(".cs") && !p.toString().contains("obj")).forEach(f -> {
                try {
                    Matcher m = Pattern.compile("(?:public|internal)\\s+class\\s+(\\w+)").matcher(Files.readString(f));
                    while (m.find()) idx.putIfAbsent(m.group(1), f);
                } catch (Exception ignored) {}
            });
        } catch (IOException ignored) {}
        return idx;
    }

    private List<ExtractedApi> parseFile(Path file, Map<String, Path> classIndex, Path root) throws IOException {
        String content = Files.readString(file);
        if (!content.contains("[ApiController]") && !content.contains("ControllerBase") && !content.contains("[HttpGet") && !content.contains("[HttpPost")) return Collections.emptyList();

        String[] lines = content.split("\\r?\\n");
        String className = firstMatch(CLASS_NAME, content, 1);
        if (className != null && className.endsWith("Controller")) className = className.substring(0, className.length() - "Controller".length());

        String baseRoute = firstMatch(ROUTE_ATTR, content, 1);
        if (baseRoute != null && className != null) baseRoute = baseRoute.replace("[controller]", className != null ? className.toLowerCase() : "");
        if (baseRoute == null) baseRoute = "";

        String relPath = root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
        List<ExtractedApi> apis = new ArrayList<>();

        List<String> pendingAnns = new ArrayList<>();
        String pendingXmlDoc = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // XML doc comment
            if (line.startsWith("///")) {
                Matcher sm = XML_SUMMARY.matcher(line); if (sm.find()) pendingXmlDoc = sm.group(1).trim();
                continue;
            }
            if (line.startsWith("[")) { pendingAnns.add(line); continue; }
            if (line.isEmpty()) continue;

            if (!pendingAnns.isEmpty()) {
                String httpMethod = null; String methodRoute = null;
                for (String ann : pendingAnns) {
                    Matcher m = HTTP_METHOD.matcher(ann);
                    if (m.find()) { httpMethod = m.group(1).toUpperCase(); methodRoute = m.group(2) != null ? m.group(2) : ""; break; }
                }

                if (httpMethod != null) {
                    // Check for method-level [Route] override in pending
                    for (String ann : pendingAnns) {
                        Matcher rm = ROUTE_ATTR.matcher(ann);
                        if (rm.find()) { methodRoute = rm.group(1); break; }
                    }

                    // Status codes from [ProducesResponseType]
                    List<Integer> codes = new ArrayList<>();
                    for (String ann : pendingAnns) {
                        Matcher pm = PRODUCES_STATUS.matcher(ann);
                        while (pm.find()) {
                            String s = pm.group(1); if (s.startsWith("StatusCodes")) s = pm.group(2);
                            try { int code = extractStatusCode(s); if (code > 0) codes.add(code); } catch (Exception ignored) {}
                        }
                    }

                    // Find method signature in next lines
                    String handlerName = null; String sigLine = null;
                    for (int j = i; j < Math.min(i + 6, lines.length); j++) {
                        String candidate = lines[j].trim();
                        if (candidate.startsWith("[") || candidate.isEmpty()) continue;
                        Matcher mm = METHOD_SIG.matcher(candidate);
                        if (mm.find()) { handlerName = mm.group(1); sigLine = collectSig(lines, j); break; }
                    }

                    // Parse parameters from signature
                    List<ApiParameter> params = sigLine != null ? parseMethodParams(sigLine) : List.of();
                    String reqBodyType = null; List<ApiField> reqBodyFields = List.of();
                    for (ApiParameter p : params) {
                        if ("BODY".equals(p.getLocation())) {
                            reqBodyType = p.getType(); reqBodyFields = resolveClass(reqBodyType, classIndex); break;
                        }
                    }
                    // Return type → response type
                    String returnType = sigLine != null ? extractReturnType(sigLine) : null;
                    List<ApiField> respFields = resolveClass(returnType, classIndex);

                    ExtractedApi api = new ExtractedApi();
                    api.setMethod(httpMethod); api.setPath(joinPaths(baseRoute, methodRoute));
                    api.setController(className); api.setHandler(handlerName); api.setDescription(pendingXmlDoc);
                    api.setParameters(params.isEmpty() ? null : params);
                    api.setRequestBodyType(reqBodyType); api.setRequestBodyFields(reqBodyFields.isEmpty() ? null : reqBodyFields);
                    api.setResponseBodyType(returnType); api.setResponseBodyFields(respFields.isEmpty() ? null : respFields);
                    api.setStatusCodes(codes.isEmpty() ? null : codes);
                    api.setSourceFile(relPath); api.setSourceLine(i + 1);
                    apis.add(api);
                }
                pendingAnns.clear(); pendingXmlDoc = null;
            } else { pendingAnns.clear(); pendingXmlDoc = null; }
        }
        return apis;
    }

    private String collectSig(String[] lines, int start) {
        StringBuilder sb = new StringBuilder(); int d = 0; boolean open = false;
        for (int i = start; i < Math.min(start + 10, lines.length); i++) {
            String l = lines[i].trim(); sb.append(" ").append(l);
            for (char c : l.toCharArray()) {
                if (c == '(') { open = true; d++; } else if (c == ')') { d--; if (open && d == 0) return sb.toString().trim(); }
            }
        }
        return sb.toString().trim();
    }

    private List<ApiParameter> parseMethodParams(String sig) {
        int s = sig.indexOf('('); if (s < 0) return List.of();
        int d = 0, end = -1;
        for (int i = s; i < sig.length(); i++) {
            char c = sig.charAt(i); if (c == '(') d++; else if (c == ')') { d--; if (d == 0) { end = i; break; } }
        }
        if (end < 0) return List.of();
        String paramStr = sig.substring(s + 1, end).trim();
        if (paramStr.isEmpty()) return List.of();

        List<ApiParameter> result = new ArrayList<>();
        for (String raw : splitComma(paramStr)) {
            ApiParameter p = parseCsParam(raw.trim());
            if (p != null) result.add(p);
        }
        return result;
    }

    private ApiParameter parseCsParam(String raw) {
        if (raw.isEmpty()) return null;
        String loc;
        if (FROM_BODY.matcher(raw).find()) loc = "BODY";
        else if (FROM_ROUTE.matcher(raw).find()) loc = "PATH";
        else if (FROM_QUERY.matcher(raw).find()) loc = "QUERY";
        else if (FROM_HEADER.matcher(raw).find()) loc = "HEADER";
        else if (FROM_FORM.matcher(raw).find()) loc = "BODY";
        else return null; // skip unannotated params (they could be anything)

        // Strip attributes to get "Type name"
        String stripped = raw.replaceAll("\\[[^\\]]*\\]\\s*", "").trim();
        String[] parts = stripped.split("\\s+");
        String type = parts.length >= 2 ? parts[parts.length - 2] : null;
        String name = parts.length >= 1 ? parts[parts.length - 1] : null;
        if (name != null) name = name.replaceAll("[^\\w]", "");

        boolean required = CS_REQUIRED.matcher(raw).find() || (!raw.contains("?") && !"QUERY".equals(loc));

        ApiParameter p = new ApiParameter();
        p.setName(name); p.setType(type); p.setLocation(loc); p.setRequired(required);
        return p;
    }

    private String extractReturnType(String sig) {
        // public async Task<ActionResult<UserDto>> GetUser(...)
        Matcher m = Pattern.compile("(?:Task<)?(?:ActionResult<|Ok<|IActionResult)?([\\w<>\\[\\]]+)>?\\s+\\w+\\s*\\(").matcher(sig);
        if (!m.find()) return null;
        String raw = m.group(1).trim();
        // Unwrap ActionResult<T>, Ok<T>
        Matcher um = Pattern.compile("(?:ActionResult|Ok|Created|NotFound|BadRequest)<([\\w<>\\[\\]]+)>").matcher(raw);
        if (um.find()) raw = um.group(1);
        return NON_DTO.contains(raw) || raw.equals("IActionResult") ? null : raw;
    }

    private List<ApiField> resolveClass(String typeName, Map<String, Path> classIndex) {
        if (typeName == null || typeName.isBlank() || NON_DTO.contains(typeName)) return List.of();
        Matcher gm = Pattern.compile("(?:List|IEnumerable|IList|ICollection)<([\\w]+)>").matcher(typeName);
        if (gm.find()) typeName = gm.group(1);
        Path f = classIndex.get(typeName); if (f == null) return List.of();
        List<ApiField> fields = new ArrayList<>();
        try {
            String[] lines = Files.readString(f).split("\\r?\\n");
            List<String> pendingReq = new ArrayList<>();
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.equals("[Required]")) { pendingReq.add("@Required"); continue; }
                Matcher fm = CS_PROP.matcher(line);
                if (fm.find()) {
                    ApiField field = new ApiField();
                    field.setName(fm.group(2)); field.setType(fm.group(1));
                    field.setValidations(pendingReq.isEmpty() ? null : new ArrayList<>(pendingReq));
                    fields.add(field); pendingReq.clear();
                } else if (!line.isEmpty() && !line.startsWith("[") && !line.startsWith("//")) { pendingReq.clear(); }
            }
        } catch (Exception ignored) {}
        return fields;
    }

    private int extractStatusCode(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        // StatusCodes.Status200OK → 200
        Matcher m = Pattern.compile("(\\d{3})").matcher(s);
        if (m.find()) { try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {} }
        return 0;
    }

    private List<String> splitComma(String s) {
        List<String> r = new ArrayList<>(); int d = 0; StringBuilder cur = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '<' || c == '(') d++; else if (c == '>' || c == ')') d--;
            else if (c == ',' && d == 0) { if (!cur.toString().isBlank()) r.add(cur.toString()); cur = new StringBuilder(); continue; }
            cur.append(c);
        }
        if (!cur.toString().isBlank()) r.add(cur.toString());
        return r;
    }

    private String joinPaths(String base, String path) {
        String b = base == null ? "" : base.replaceAll("/$", "");
        String p = path == null ? "" : (path.startsWith("/") ? path : (path.isEmpty() ? "" : "/" + path));
        String j = b + p; return j.isEmpty() ? "/" : (j.startsWith("/") ? j : "/" + j);
    }

    private String firstMatch(Pattern pat, String text, int group) {
        Matcher m = pat.matcher(text); if (m.find()) { try { return m.group(group); } catch (Exception ignored) {} } return null;
    }
}
