package com.apicatalog.service.context;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Walks a repository once and builds a Map of type-name → Path.
 * Generalizes the per-parser class index logic into a single shared component.
 *
 * Supports Java, TypeScript/JS, Python, Go, and C#.
 */
@Component
public class TypeIndexBuilder {

    // Java / C#
    private static final Pattern JAVA_CSHARP = Pattern.compile(
            "^\\s*(?:public|private|internal|protected)?\\s+(?:abstract|partial|static\\s+)?(?:class|record|interface|enum)\\s+(\\w+)");

    // TypeScript / JavaScript
    private static final Pattern TS_JS = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:default\\s+)?(?:abstract\\s+)?(?:class|interface|type|enum)\\s+(\\w+)");

    // Python
    private static final Pattern PYTHON = Pattern.compile(
            "^class\\s+(\\w+)");

    // Go
    private static final Pattern GO = Pattern.compile(
            "^type\\s+(\\w+)\\s+struct");

    /**
     * Builds the index from all source files under {@code root},
     * excluding test directories.
     */
    public Map<String, Path> build(Path root) {
        Map<String, Path> index = new HashMap<>();
        try {
            Files.walk(root)
                    .filter(p -> isSourceFile(p.toString()))
                    .filter(p -> !isTestPath(p.toString()))
                    .forEach(file -> {
                        try {
                            indexFile(file, index);
                        } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
        return index;
    }

    /**
     * Extracts the source of a named type from the indexed file.
     * Returns the type declaration plus its body (brace-matched).
     */
    public String extractTypeSource(String typeName, Map<String, Path> index) {
        Path file = index.get(typeName);
        if (file == null) return "";
        try {
            String[] lines = Files.readString(file).split("\\r?\\n");
            Pattern declaration = declarationPattern(file.getFileName().toString(), typeName);
            for (int i = 0; i < lines.length; i++) {
                if (declaration.matcher(lines[i]).find()) {
                    return extractBody(lines, i, file.getFileName().toString());
                }
            }
        } catch (IOException ignored) {}
        return "";
    }

    // ── Private helpers ────────────────────────────────────────

    private void indexFile(Path file, Map<String, Path> index) throws IOException {
        String fname = file.getFileName().toString();
        Pattern pattern = patternForFile(fname);
        if (pattern == null) return;
        for (String line : Files.readAllLines(file)) {
            Matcher m = pattern.matcher(line.trim());
            if (m.find()) {
                index.putIfAbsent(m.group(1), file);
                break; // first class/type per file wins
            }
        }
    }

    private Pattern patternForFile(String fname) {
        if (fname.endsWith(".java") || fname.endsWith(".cs")) return JAVA_CSHARP;
        if (fname.endsWith(".ts") || fname.endsWith(".tsx") ||
            fname.endsWith(".js") || fname.endsWith(".jsx")) return TS_JS;
        if (fname.endsWith(".py")) return PYTHON;
        if (fname.endsWith(".go")) return GO;
        return null;
    }

    private Pattern declarationPattern(String fname, String typeName) {
        String escaped = Pattern.quote(typeName);
        if (fname.endsWith(".py")) return Pattern.compile("^class\\s+" + escaped + "\\b");
        if (fname.endsWith(".go")) return Pattern.compile("^type\\s+" + escaped + "\\s+struct");
        return Pattern.compile("\\b(?:class|record|interface|type|struct)\\s+" + escaped + "\\b");
    }

    private String extractBody(String[] lines, int start, String fname) {
        StringBuilder sb = new StringBuilder();
        if (fname.endsWith(".py")) {
            int baseIndent = leadingSpaces(lines[start]);
            sb.append(lines[start]).append("\n");
            for (int i = start + 1; i < lines.length && i < start + 100; i++) {
                String line = lines[i];
                if (!line.isBlank() && leadingSpaces(line) <= baseIndent) break;
                sb.append(line).append("\n");
            }
        } else {
            int depth = 0; boolean opened = false;
            for (int i = start; i < Math.min(lines.length, start + 100); i++) {
                sb.append(lines[i]).append("\n");
                for (char c : lines[i].toCharArray()) {
                    if (c == '{') { opened = true; depth++; }
                    else if (c == '}') depth--;
                }
                if (opened && depth == 0) break;
            }
        }
        return sb.toString().trim();
    }

    private boolean isSourceFile(String path) {
        return path.endsWith(".java") || path.endsWith(".ts") || path.endsWith(".tsx") ||
               path.endsWith(".js") || path.endsWith(".py") || path.endsWith(".go") ||
               path.endsWith(".cs");
    }

    private boolean isTestPath(String path) {
        return path.contains("/test/") || path.contains("\\test\\") ||
               path.contains("/tests/") || path.contains("\\tests\\") ||
               path.contains("_test.go") || path.contains(".spec.") ||
               path.contains(".test.");
    }

    private int leadingSpaces(String line) {
        int count = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') count++; else if (c == '\t') count += 4; else break;
        }
        return count;
    }
}
