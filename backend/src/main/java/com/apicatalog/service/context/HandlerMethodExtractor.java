package com.apicatalog.service.context;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Extracts the complete body of a handler method from source code,
 * using brace-matching for C-family languages and indentation for Python.
 *
 * The {@code sourceLine} recorded by the parser is the line where the
 * method signature starts (1-based). We read forward from there.
 */
@Component
public class HandlerMethodExtractor {

    private static final int MAX_LINES = 200;

    /**
     * Returns the handler method body (including its surrounding annotations
     * and signature) as a plain string. Returns an empty string on any error.
     */
    public String extract(Path file, int sourceLine) {
        if (file == null || !Files.exists(file) || sourceLine <= 0) return "";
        try {
            String[] lines = Files.readString(file).split("\\r?\\n");
            String fileName = file.getFileName().toString();
            if (fileName.endsWith(".py")) {
                return extractPython(lines, sourceLine);
            } else {
                return extractBraceLanguage(lines, sourceLine);
            }
        } catch (IOException ignored) {
            return "";
        }
    }

    // ── Java / TypeScript / JS / Go / C# ──────────────────────

    private String extractBraceLanguage(String[] lines, int sourceLine) {
        StringBuilder sb = new StringBuilder();

        // Walk backward from sourceLine to capture annotations above the method
        int start = sourceLine - 1; // 0-indexed
        while (start > 0 && lines[start - 1].trim().startsWith("@")) {
            start--;
        }

        int depth = 0;
        boolean opened = false;

        for (int i = start; i < Math.min(lines.length, start + MAX_LINES); i++) {
            String line = lines[i];
            sb.append(line).append("\n");
            for (char c : line.toCharArray()) {
                if (c == '{') { opened = true; depth++; }
                else if (c == '}') depth--;
            }
            if (opened && depth == 0) break;
        }
        return sb.toString().trim();
    }

    // ── Python (indentation-based) ─────────────────────────────

    private String extractPython(String[] lines, int sourceLine) {
        int idx = sourceLine - 1; // 0-indexed
        if (idx >= lines.length) return "";

        String defLine = lines[idx];
        int baseIndent = leadingSpaces(defLine);

        // Walk backward to capture decorators
        int start = idx;
        while (start > 0 && lines[start - 1].trim().startsWith("@")) {
            start--;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length && (i - start) < MAX_LINES; i++) {
            String line = lines[i];
            sb.append(line).append("\n");
            if (i > idx && !line.isBlank()) {
                int indent = leadingSpaces(line);
                if (indent <= baseIndent) break; // back to same/lower level
            }
        }
        return sb.toString().trim();
    }

    private int leadingSpaces(String line) {
        int count = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') count++;
            else if (c == '\t') count += 4;
            else break;
        }
        return count;
    }
}
