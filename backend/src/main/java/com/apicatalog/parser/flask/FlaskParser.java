package com.apicatalog.parser.flask;

import com.apicatalog.model.*;
import com.apicatalog.parser.ParserPlugin;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

@Component
public class FlaskParser implements ParserPlugin {

    private static final Pattern ROUTE = Pattern.compile(
        "^@[\\w.]+\\.route\\s*\\(\\s*[\"']([^\"']+)[\"'](?:.*?methods\\s*=\\s*\\[([^\\]]+)\\])?",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORTHAND = Pattern.compile(
        "^@[\\w.]+\\.(get|post|put|delete|patch)\\s*\\(\\s*[\"']([^\"']+)[\"']",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern FUNC_DEF = Pattern.compile("^(?:async\\s+)?def\\s+(\\w+)\\s*\\(");
    private static final Pattern URL_PARAM = Pattern.compile("<(?:(\\w+):)?(\\w+)>");
    private static final Pattern RETURN_STATUS = Pattern.compile("return\\s+.+?,\\s*(\\d{3})\\s*$");
    private static final Pattern ABORT = Pattern.compile("abort\\s*\\(\\s*(\\d{3})");
    private static final Pattern REQ_BODY = Pattern.compile("request\\.(?:json|get_json|data|form)");
    private static final Pattern REQ_ARGS = Pattern.compile("request\\.args\\.get\\s*\\(\\s*[\"']([^\"']+)[\"']");
    private static final Pattern REQ_FORM = Pattern.compile("request\\.form\\.get\\s*\\(\\s*[\"']([^\"']+)[\"']");

    @Override public String getFrameworkName() { return "Flask"; }

    @Override
    public boolean supports(Path root) {
        return hasDep(root, "flask") && !hasDep(root, "fastapi");
    }

    @Override
    public List<ExtractedApi> extract(Path root) {
        List<ExtractedApi> apis = new ArrayList<>();
        try {
            Files.walk(root)
                .filter(p -> p.toString().endsWith(".py"))
                .filter(p -> !p.getFileName().toString().startsWith("test_") && !p.toString().contains("/tests/"))
                .forEach(f -> { try { apis.addAll(parseFile(f, root)); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
        return apis;
    }

    private List<ExtractedApi> parseFile(Path file, Path root) throws IOException {
        String[] lines = Files.readString(file).split("\\r?\\n");
        List<ExtractedApi> apis = new ArrayList<>();
        String relPath = root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            List<String> methods = null; String path = null;
            Matcher rm = ROUTE.matcher(trimmed);
            if (rm.find()) { path = rm.group(1); methods = parseMethods(rm.group(2)); }
            else { Matcher sm = SHORTHAND.matcher(trimmed); if (sm.find()) { methods = List.of(sm.group(1).toUpperCase()); path = sm.group(2); } }
            if (path == null) continue;
            String handlerName = null; int funcLine = -1;
            for (int j = i + 1; j < Math.min(i + 5, lines.length); j++) {
                Matcher fm = FUNC_DEF.matcher(lines[j].trim());
                if (fm.find()) { handlerName = fm.group(1); funcLine = j; break; }
            }
            String desc = null;
            if (funcLine >= 0) {
                for (int j = funcLine + 1; j < Math.min(funcLine + 4, lines.length); j++) {
                    String dl = lines[j].trim();
                    if (dl.startsWith("\"\"\"")) { String ds = dl.replace("\"\"\"", "").trim(); if (!ds.isEmpty()) desc = ds; break; }
                    if (!dl.isEmpty()) break;
                }
            }
            List<ApiParameter> params = new ArrayList<>();
            Matcher urlPm = URL_PARAM.matcher(path);
            while (urlPm.find()) {
                String convType = urlPm.group(1); String pName = urlPm.group(2);
                String type = convType == null ? "string" : switch (convType) { case "int" -> "integer"; case "float" -> "number"; default -> "string"; };
                ApiParameter p = new ApiParameter(); p.setName(pName); p.setType(type); p.setLocation("PATH"); p.setRequired(true); params.add(p);
            }
            Set<String> queryNames = new LinkedHashSet<>(); Set<String> formFields = new LinkedHashSet<>();
            boolean hasBody = false; List<Integer> codes = new ArrayList<>();
            for (int j = (funcLine >= 0 ? funcLine : i) + 1; j < Math.min((funcLine >= 0 ? funcLine : i) + 50, lines.length); j++) {
                String bl = lines[j];
                if (!bl.startsWith("    ") && !bl.startsWith("\t") && !bl.trim().isEmpty()) break;
                Matcher am = REQ_ARGS.matcher(bl); while (am.find()) queryNames.add(am.group(1));
                Matcher ffm = REQ_FORM.matcher(bl); while (ffm.find()) formFields.add(ffm.group(1));
                if (REQ_BODY.matcher(bl).find()) hasBody = true;
                Matcher rsm = RETURN_STATUS.matcher(bl.trim());
                if (rsm.find()) { try { int c = Integer.parseInt(rsm.group(1)); if (!codes.contains(c)) codes.add(c); } catch (Exception ignored) {} }
                Matcher abm = ABORT.matcher(bl);
                if (abm.find()) { try { int c = Integer.parseInt(abm.group(1)); if (!codes.contains(c)) codes.add(c); } catch (Exception ignored) {} }
            }
            for (String qn : queryNames) { ApiParameter p = new ApiParameter(); p.setName(qn); p.setType("string"); p.setLocation("QUERY"); p.setRequired(false); params.add(p); }
            String reqBodyType = null; List<ApiField> reqBodyFields = List.of();
            if (hasBody || !formFields.isEmpty()) {
                reqBodyType = "object";
                if (!formFields.isEmpty()) { List<ApiField> fList = new ArrayList<>(); for (String fn : formFields) { ApiField f = new ApiField(); f.setName(fn); f.setType("string"); fList.add(f); } reqBodyFields = fList; }
            }
            for (String method : methods) {
                ExtractedApi api = new ExtractedApi();
                api.setMethod(method); api.setPath(path); api.setHandler(handlerName); api.setDescription(desc);
                api.setParameters(params.isEmpty() ? null : params);
                api.setRequestBodyType(reqBodyType); api.setRequestBodyFields(reqBodyFields.isEmpty() ? null : reqBodyFields);
                api.setStatusCodes(codes.isEmpty() ? null : codes);
                api.setSourceFile(relPath); api.setSourceLine(i + 1);
                apis.add(api);
            }
        }
        return apis;
    }

    private List<String> parseMethods(String s) {
        if (s == null || s.isBlank()) return List.of("GET");
        List<String> r = new ArrayList<>();
        for (String m : s.split(",")) { String c = m.trim().replace("'", "").replace("\"", "").toUpperCase(); if (!c.isEmpty() && !c.equals("HEAD") && !c.equals("OPTIONS")) r.add(c); }
        return r.isEmpty() ? List.of("GET") : r;
    }

    private boolean hasDep(Path root, String dep) {
        for (String f : List.of("requirements.txt", "pyproject.toml", "Pipfile")) {
            Path c = root.resolve(f); if (Files.exists(c)) { try { if (Files.readString(c).toLowerCase().contains(dep)) return true; } catch (IOException ignored) {} }
        }
        return false;
    }
}