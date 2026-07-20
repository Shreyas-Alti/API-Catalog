package com.apicatalog.parser.springboot;

import com.apicatalog.model.*;
import com.apicatalog.parser.ParserPlugin;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

@Component
public class SpringBootParser implements ParserPlugin {

    private static final Pattern HTTP_MAPPING = Pattern.compile(
        "@(Get|Post|Put|Delete|Patch)Mapping(?:\\(\\s*(?:value\\s*=\\s*)?[\"']([^\"']*)[\"']\\s*\\))?");
    private static final Pattern REQUEST_MAPPING_METHOD = Pattern.compile(
        "@RequestMapping\\([^)]*method\\s*=\\s*RequestMethod\\.(\\w+)");
    private static final Pattern CLASS_MAPPING = Pattern.compile(
        "@RequestMapping\\(\\s*(?:value\\s*=\\s*)?[\"']([^\"']+)[\"']");
    private static final Pattern CLASS_NAME_PAT = Pattern.compile(
        "(?:public|protected|private)?\\s+class\\s+(\\w+)");
    private static final Pattern TAG_PAT = Pattern.compile(
        "@Tag\\s*\\(\\s*name\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern OPERATION_SUMMARY = Pattern.compile(
        "@Operation\\s*\\([^)]*summary\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern RESPONSE_STATUS = Pattern.compile(
        "@ResponseStatus\\s*\\(\\s*(?:value\\s*=\\s*)?HttpStatus\\.(\\w+)");
    private static final Pattern API_RESPONSE_CODE = Pattern.compile(
        "@ApiResponse\\s*\\([^)]*responseCode\\s*=\\s*[\"'](\\d+)[\"']");
    private static final Pattern METHOD_NAME_PAT = Pattern.compile(
        "(?:public|protected|private)?\\s+(?:static\\s+)?[\\w<>\\[\\],?\\s]+\\s+(\\w+)\\s*\\(");
    private static final Pattern RETURN_TYPE_PAT = Pattern.compile(
        "(?:public|protected|private)\\s+(?:static\\s+)?([\\w<>\\[\\],?\\s]+?)\\s+\\w+\\s*\\(");
    private static final Pattern PATH_VAR = Pattern.compile(
        "@PathVariable(?:\\(\\s*(?:value\\s*=\\s*)?[\"']([^\"']*)[\"']\\s*\\))?");
    private static final Pattern REQ_PARAM = Pattern.compile(
        "@RequestParam(?:\\(([^)]*)\\))?");
    private static final Pattern REQ_HEADER = Pattern.compile(
        "@RequestHeader(?:\\(\\s*(?:value\\s*=\\s*)?[\"']([^\"']*)[\"']\\s*\\))?");
    private static final Pattern COOKIE_VAL = Pattern.compile(
        "@CookieValue(?:\\(\\s*(?:value\\s*=\\s*)?[\"']([^\"']*)[\"']\\s*\\))?");
    private static final Pattern FIELD_DECL = Pattern.compile(
        "private\\s+([\\w<>\\[\\],?\\s]+?)\\s+(\\w+)\\s*[;=]");
    private static final Pattern VALIDATION = Pattern.compile(
        "@(NotNull|NotBlank|NotEmpty|Email|Min|Max|Size|Pattern|Positive|PositiveOrZero|"
        + "Negative|NegativeOrZero|DecimalMin|DecimalMax|Future|Past|AssertTrue|AssertFalse)"
        + "(?:\\(([^)]*)\\))?");
    private static final Set<String> NON_DTO = Set.of(
        "void","Void","String","Integer","Long","Boolean","Double","Float",
        "int","long","boolean","double","float","byte","short","char",
        "Object","UUID","Instant","LocalDate","LocalDateTime","ZonedDateTime",
        "OffsetDateTime","BigDecimal","BigInteger","Number",
        "Map","HashMap","List","ArrayList","Set","HashSet","Collection",
        "Optional","Page","Pageable","MultipartFile","HttpServletRequest",
        "HttpServletResponse","Principal","Authentication","Model","ModelAndView"
    );

    @Override public String getFrameworkName() { return "Spring Boot"; }

    @Override
    public boolean supports(Path root) {
        for (String f : List.of("pom.xml", "build.gradle", "build.gradle.kts")) {
            Path p = root.resolve(f);
            if (Files.exists(p)) {
                try { if (Files.readString(p).contains("spring-boot")) return true; }
                catch (IOException ignored) {}
            }
        }
        return false;
    }

    @Override
    public List<ExtractedApi> extract(Path root) {
        Map<String, Path> idx = buildIndex(root);
        List<ExtractedApi> apis = new ArrayList<>();
        idx.forEach((n, f) -> {
            try {
                String c = Files.readString(f);
                if (c.contains("@RestController") || c.contains("@Controller")) {
                    String rel = root.relativize(f).toString().replace(File.separatorChar, '/');
                    apis.addAll(parseController(f, rel, idx));
                }
            } catch (Exception ignored) {}
        });
        return apis;
    }

    private Map<String, Path> buildIndex(Path root) {
        Map<String, Path> index = new HashMap<>();
        try {
            Files.walk(root)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.toString().contains(File.separator + "test" + File.separator))
                .forEach(f -> {
                    try {
                        for (String line : Files.readAllLines(f)) {
                            Matcher m = CLASS_NAME_PAT.matcher(line.trim());
                            if (m.find()) { index.put(m.group(1), f); break; }
                        }
                    } catch (Exception ignored) {}
                });
        } catch (IOException ignored) {}
        return index;
    }

    private List<ExtractedApi> parseController(Path file, String relPath,
                                               Map<String, Path> idx) throws IOException {
        String[] lines = Files.readString(file).split("\\r?\\n");
        String cls = null, basePath = "";
        List<String> clsTags = new ArrayList<>();
        for (String line : lines) {
            String t = line.trim();
            if (cls == null) { Matcher m = CLASS_NAME_PAT.matcher(t); if (m.find()) cls = m.group(1); }
            Matcher bm = CLASS_MAPPING.matcher(t); if (bm.find()) basePath = bm.group(1);
            Matcher tm = TAG_PAT.matcher(t); if (tm.find()) clsTags.add(tm.group(1));
        }
        List<ExtractedApi> apis = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.isEmpty() || t.startsWith("//") || t.startsWith("*")) continue;
            if (t.startsWith("@")) { pending.add(t); continue; }
            if (!pending.isEmpty()) {
                String httpMethod = null, annPath = "";
                for (String ann : pending) {
                    Matcher hm = HTTP_MAPPING.matcher(ann);
                    if (hm.find()) {
                        httpMethod = hm.group(1).toUpperCase();
                        annPath = hm.group(2) != null ? hm.group(2) : "";
                        break;
                    }
                    Matcher rm = REQUEST_MAPPING_METHOD.matcher(ann);
                    if (rm.find()) httpMethod = rm.group(1).toUpperCase();
                }
                if (httpMethod != null) {
                    String sig = collectSig(lines, i);
                    if (sig != null) {
                        ExtractedApi api = buildApi(httpMethod, annPath, sig, pending,
                            cls, basePath, clsTags, relPath, i + 1, idx);
                        if (api != null) apis.add(api);
                    }
                }
            }
            pending.clear();
        }
        return apis;
    }

    private String collectSig(String[] lines, int start) {
        StringBuilder sb = new StringBuilder(); int d = 0; boolean open = false;
        for (int i = start; i < Math.min(start + 15, lines.length); i++) {
            String l = lines[i].trim();
            if (l.startsWith("@") || l.startsWith("//")) continue;
            sb.append(" ").append(l);
            for (char c : l.toCharArray()) {
                if (c == '(') { open = true; d++; }
                else if (c == ')') d--;
            }
            if (open && d == 0) break;
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private ExtractedApi buildApi(String http, String annPath, String sig,
            List<String> anns, String cls, String base, List<String> clsTags,
            String relPath, int lineNum, Map<String, Path> idx) {
        Matcher mn = METHOD_NAME_PAT.matcher(sig);
        if (!mn.find()) return null;
        String handler = mn.group(1);
        String desc = null;
        for (String a : anns) { Matcher om = OPERATION_SUMMARY.matcher(a); if (om.find()) { desc = om.group(1); break; } }
        List<Integer> codes = new ArrayList<>();
        for (String a : anns) {
            Matcher rs = RESPONSE_STATUS.matcher(a); if (rs.find()) codes.add(statusCode(rs.group(1)));
            Matcher ar = API_RESPONSE_CODE.matcher(a);
            if (ar.find()) { try { codes.add(Integer.parseInt(ar.group(1))); } catch (NumberFormatException ignored) {} }
        }
        List<String> tags = new ArrayList<>(clsTags);
        for (String a : anns) { Matcher tm = TAG_PAT.matcher(a); if (tm.find()) tags.add(tm.group(1)); }
        List<ApiParameter> params = parseParams(paramList(sig));
        String reqType = null; List<ApiField> reqFields = List.of();
        for (ApiParameter p : params) {
            if ("BODY".equals(p.getLocation())) { reqType = p.getType(); reqFields = resolveDto(reqType, idx); break; }
        }
        String respType = returnType(sig);
        List<ApiField> respFields = resolveDto(respType, idx);
        ExtractedApi api = new ExtractedApi();
        api.setMethod(http); api.setPath(joinPaths(base, annPath));
        api.setController(cls); api.setHandler(handler); api.setDescription(desc);
        api.setTags(tags.isEmpty() ? null : tags);
        api.setParameters(params.isEmpty() ? null : params);
        api.setRequestBodyType(reqType); api.setRequestBodyFields(reqFields.isEmpty() ? null : reqFields);
        api.setResponseBodyType(respType); api.setResponseBodyFields(respFields.isEmpty() ? null : respFields);
        api.setStatusCodes(codes.isEmpty() ? null : codes);
        api.setSourceFile(relPath); api.setSourceLine(lineNum);
        return api;
    }

    private String paramList(String sig) {
        int s = sig.indexOf('('); if (s < 0) return "";
        int d = 0;
        for (int i = s; i < sig.length(); i++) {
            char c = sig.charAt(i);
            if (c == '(') d++; else if (c == ')') { d--; if (d == 0) return sig.substring(s + 1, i); }
        }
        return "";
    }

    private List<ApiParameter> parseParams(String list) {
        if (list == null || list.isBlank()) return List.of();
        return splitComma(list).stream().map(String::trim)
            .map(this::parseParam).filter(Objects::nonNull).collect(Collectors.toList());
    }

    private ApiParameter parseParam(String raw) {
        if (raw.isEmpty()) return null;
        String loc; String nameAnn = null; boolean req = true;
        if (raw.contains("@PathVariable")) {
            loc = "PATH";
            Matcher m = PATH_VAR.matcher(raw); if (m.find() && m.group(1) != null) nameAnn = m.group(1);
        } else if (raw.contains("@RequestParam")) {
            loc = "QUERY";
            Matcher m = REQ_PARAM.matcher(raw);
            if (m.find() && m.group(1) != null) {
                String opts = m.group(1);
                Matcher nm = Pattern.compile("(?:name|value)\\s*=\\s*[\"']([^\"']+)[\"']").matcher(opts);
                if (nm.find()) nameAnn = nm.group(1);
                req = !opts.contains("required = false") && !opts.contains("required=false");
            }
        } else if (raw.contains("@RequestBody")) {
            loc = "BODY"; req = !raw.contains("required = false");
        } else if (raw.contains("@RequestHeader")) {
            loc = "HEADER";
            Matcher m = REQ_HEADER.matcher(raw); if (m.find() && m.group(1) != null) nameAnn = m.group(1);
        } else if (raw.contains("@CookieValue")) {
            loc = "COOKIE";
            Matcher m = COOKIE_VAL.matcher(raw); if (m.find() && m.group(1) != null) nameAnn = m.group(1);
        } else { return null; }
        String stripped = raw.replaceAll("@\\w+(?:\\([^)]*\\))?\\s*", "").trim();
        String[] parts = stripped.split("\\s+");
        String type = parts.length >= 2 ? parts[parts.length - 2] : null;
        String name = parts.length >= 1 ? parts[parts.length - 1] : null;
        if (nameAnn != null && !nameAnn.isEmpty()) name = nameAnn;
        List<String> vals = new ArrayList<>();
        Matcher vm = VALIDATION.matcher(raw);
        while (vm.find()) {
            String a = "@" + vm.group(1);
            if (vm.group(2) != null && !vm.group(2).isBlank()) a += "(" + vm.group(2).trim() + ")";
            vals.add(a);
        }
        ApiParameter p = new ApiParameter();
        p.setName(name); p.setType(type); p.setLocation(loc); p.setRequired(req);
        p.setValidations(vals.isEmpty() ? null : vals);
        return p;
    }

    private List<ApiField> resolveDto(String typeName, Map<String, Path> idx) {
        if (typeName == null || typeName.isBlank()) return List.of();
        Matcher gm = Pattern.compile("(?:List|Set|Collection|Page|Optional)<([\\w<>\\[\\]?,\\s]+)>").matcher(typeName);
        if (gm.find()) typeName = gm.group(1).trim();
        if (NON_DTO.contains(typeName)) return List.of();
        Path cf = idx.get(typeName); if (cf == null) return List.of();
        List<ApiField> fields = new ArrayList<>();
        try {
            String[] lines = Files.readString(cf).split("\\r?\\n");
            List<String> pendingV = new ArrayList<>();
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.startsWith("@")) {
                    Matcher vm = VALIDATION.matcher(line);
                    while (vm.find()) {
                        String a = "@" + vm.group(1);
                        if (vm.group(2) != null && !vm.group(2).isBlank()) a += "(" + vm.group(2).trim() + ")";
                        pendingV.add(a);
                    }
                    continue;
                }
                Matcher fm = FIELD_DECL.matcher(line);
                if (fm.find()) {
                    ApiField f = new ApiField(); f.setType(fm.group(1).trim()); f.setName(fm.group(2).trim());
                    f.setValidations(pendingV.isEmpty() ? null : new ArrayList<>(pendingV));
                    fields.add(f); pendingV.clear();
                } else if (!line.isEmpty()) { pendingV.clear(); }
            }
        } catch (Exception ignored) {}
        return fields;
    }

    private String returnType(String sig) {
        Matcher m = RETURN_TYPE_PAT.matcher(sig); if (!m.find()) return null;
        String t = m.group(1).trim();
        Matcher re = Pattern.compile("ResponseEntity<(.+)>$").matcher(t); if (re.find()) t = re.group(1).trim();
        Matcher rx = Pattern.compile("(?:Mono|Flux)<(.+)>$").matcher(t); if (rx.find()) t = rx.group(1).trim();
        return (t.isEmpty() || "void".equals(t)) ? null : t;
    }

    private List<String> splitComma(String s) {
        List<String> r = new ArrayList<>(); int d = 0; StringBuilder cur = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '<' || c == '(' || c == '[') d++;
            else if (c == '>' || c == ')' || c == ']') d--;
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

    private int statusCode(String name) {
        return switch (name.toUpperCase()) {
            case "CREATED" -> 201; case "ACCEPTED" -> 202; case "NO_CONTENT" -> 204;
            case "BAD_REQUEST" -> 400; case "UNAUTHORIZED" -> 401; case "FORBIDDEN" -> 403;
            case "NOT_FOUND" -> 404; case "CONFLICT" -> 409; case "UNPROCESSABLE_ENTITY" -> 422;
            case "INTERNAL_SERVER_ERROR" -> 500; default -> 200;
        };
    }
}
