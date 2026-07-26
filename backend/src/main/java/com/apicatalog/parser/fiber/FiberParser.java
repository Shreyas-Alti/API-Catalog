package com.apicatalog.parser.fiber;

import com.apicatalog.model.ExtractedApi;
import com.apicatalog.parser.ParserPlugin;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

/**
 * Parser for Fiber (Go) projects.
 * Handles both single-line and gofmt-wrapped multi-line route registrations.
 */
@Component
public class FiberParser implements ParserPlugin {

    // Match the start of a route call — handler resolved via paren-balancing below
    private static final Pattern ROUTE = Pattern.compile(
            "^\\s*[\\w]+\\.(Get|Post|Put|Delete|Patch|All)\\s*\\(\\s*\"([^\"]+)\"");

    @Override
    public String getFrameworkName() { return "Fiber"; }

    @Override
    public boolean supports(Path repositoryRoot) {
        Path goMod = repositoryRoot.resolve("go.mod");
        if (!Files.exists(goMod)) return false;
        try {
            return Files.readString(goMod).contains("gofiber/fiber");
        } catch (IOException e) { return false; }
    }

    @Override
    public List<ExtractedApi> extract(Path repositoryRoot) {
        List<ExtractedApi> apis = new ArrayList<>();
        try {
            Files.walk(repositoryRoot)
                    .filter(p -> p.toString().endsWith(".go"))
                    .filter(p -> !p.toString().contains("vendor"))
                    .filter(p -> !p.getFileName().toString().endsWith("_test.go"))
                    .forEach(f -> {
                        try { apis.addAll(parseFile(f)); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
        return apis;
    }

    private List<ExtractedApi> parseFile(Path file) throws IOException {
        String[] lines = Files.readAllLines(file).toArray(new String[0]);
        List<ExtractedApi> apis = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher m = ROUTE.matcher(lines[i]);
            if (!m.find()) continue;
            String verb = m.group(1);
            if (verb.equals("All")) verb = "GET";
            ExtractedApi api = new ExtractedApi();
            api.setMethod(verb.toUpperCase());
            api.setPath(m.group(2));
            // Resolve handler from the full paren-balanced call (handles multi-line + multi-middleware)
            api.setHandler(resolveLastArg(lines, i));
            apis.add(api);
        }
        return apis;
    }

    /** Walk forward until the route call's parens balance; return the last identifier seen. */
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
                        // Capture identifiers up to the closing paren on this line
                        Matcher idm = Pattern.compile("\\b([A-Za-z_]\\w*)\\b")
                                .matcher(lines[i].substring(0, ci));
                        while (idm.find()) {
                            String id = idm.group(1);
                            if (!id.equals("nil") && !id.equals("true") && !id.equals("false") && started && depth == 0)
                                lastIdent = id;
                        }
                        return lastIdent;
                    }
                }
            }
            // Fully-interior line: capture all identifiers
            if (started && depth >= 1) {
                Matcher idm = Pattern.compile("\\b([A-Za-z_]\\w*)\\b").matcher(lines[i]);
                while (idm.find()) {
                    String id = idm.group(1);
                    if (!id.equals("nil") && !id.equals("true") && !id.equals("false"))
                        lastIdent = id;
                }
            }
        }
        return lastIdent;
    }
}
