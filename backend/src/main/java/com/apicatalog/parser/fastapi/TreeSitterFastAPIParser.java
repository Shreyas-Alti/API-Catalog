package com.apicatalog.parser.fastapi;

import com.apicatalog.model.*;
import com.apicatalog.parser.ParserPlugin;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * FastAPI parser backed by tree-sitter (Node.js subprocess).
 * Falls back to the regex-based parser if the tree-sitter CLI is unavailable.
 */
@Component
@Order(0)  // Higher priority than regex fallback
public class TreeSitterFastAPIParser implements ParserPlugin {

    private static final Logger log = LoggerFactory.getLogger(TreeSitterFastAPIParser.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @Value("${parser.treesitter.node-path:node}")
    private String nodePath;

    @Value("${parser.treesitter.script-dir:#{null}}")
    private String scriptDirOverride;

    @Value("${parser.treesitter.timeout-seconds:120}")
    private int timeoutSeconds;

    @Value("${parser.treesitter.enabled:true}")
    private boolean enabled;

    private final FastAPIParser regexFallback;

    public TreeSitterFastAPIParser(FastAPIParser regexFallback) {
        this.regexFallback = regexFallback;
    }

    @Override
    public String getFrameworkName() { return "FastAPI"; }

    @Override
    public boolean supports(Path root) { return regexFallback.supports(root); }

    @Override
    public List<ExtractedApi> extract(Path root) {
        if (!enabled) {
            log.debug("Tree-sitter parser disabled, using regex fallback");
            return regexFallback.extract(root);
        }

        Path scriptDir = resolveScriptDir();
        if (scriptDir == null || !Files.exists(scriptDir.resolve("src/index.js"))) {
            log.warn("Tree-sitter parser script not found at {}, falling back to regex", scriptDir);
            return regexFallback.extract(root);
        }

        try {
            String jsonOutput = invokeNodeCli(root, scriptDir);
            return parseOutput(jsonOutput);
        } catch (Exception e) {
            log.warn("Tree-sitter parser failed ({}), falling back to regex: {}", e.getClass().getSimpleName(), e.getMessage());
            return regexFallback.extract(root);
        }
    }

    private Path resolveScriptDir() {
        if (scriptDirOverride != null && !scriptDirOverride.isBlank()) {
            return Path.of(scriptDirOverride);
        }
        // Default: ../treesitter-parser relative to backend working dir
        Path candidate = Path.of("").toAbsolutePath().getParent();
        if (candidate != null) {
            Path tsDir = candidate.resolve("treesitter-parser");
            if (Files.exists(tsDir)) return tsDir;
        }
        return null;
    }

    private String invokeNodeCli(Path repoRoot, Path scriptDir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
            nodePath, "src/index.js"
        );
        pb.directory(scriptDir.toFile());
        pb.redirectErrorStream(false);
        pb.environment().put("NODE_ENV", "production");

        Process proc = pb.start();

        // Write input JSON to stdin
        String input = mapper.writeValueAsString(Map.of("rootPath", repoRoot.toAbsolutePath().toString()));
        try (OutputStream stdin = proc.getOutputStream()) {
            stdin.write(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            stdin.flush();
        }

        // Read stdout
        String stdout;
        try (InputStream is = proc.getInputStream()) {
            stdout = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        // Read stderr for diagnostics
        String stderr;
        try (InputStream es = proc.getErrorStream()) {
            stderr = new String(es.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            throw new IOException("Tree-sitter parser timed out after " + timeoutSeconds + "s");
        }

        int exitCode = proc.exitValue();
        if (exitCode != 0) {
            throw new IOException("Tree-sitter parser exited with code " + exitCode + ": " + stderr.trim());
        }

        if (!stderr.isBlank()) {
            log.debug("Tree-sitter stderr: {}", stderr.trim());
        }

        return stdout;
    }

    private List<ExtractedApi> parseOutput(String json) throws IOException {
        JsonNode root = mapper.readTree(json);
        JsonNode apisNode = root.get("apis");
        if (apisNode == null || !apisNode.isArray()) return List.of();

        List<ExtractedApi> apis = new ArrayList<>();
        for (JsonNode apiNode : apisNode) {
            ExtractedApi api = new ExtractedApi();
            api.setMethod(text(apiNode, "method"));
            api.setPath(text(apiNode, "path"));
            api.setController(text(apiNode, "controller"));
            api.setHandler(text(apiNode, "handler"));
            api.setDescription(text(apiNode, "description"));
            api.setRequestBodyType(text(apiNode, "requestBodyType"));
            api.setResponseBodyType(text(apiNode, "responseBodyType"));
            api.setSourceFile(text(apiNode, "sourceFile"));

            JsonNode slNode = apiNode.get("sourceLine");
            if (slNode != null && slNode.isInt()) api.setSourceLine(slNode.intValue());

            // Tags
            JsonNode tagsNode = apiNode.get("tags");
            if (tagsNode != null && tagsNode.isArray()) {
                List<String> tags = new ArrayList<>();
                for (JsonNode t : tagsNode) tags.add(t.asText());
                api.setTags(tags);
            }

            // Status codes
            JsonNode codesNode = apiNode.get("statusCodes");
            if (codesNode != null && codesNode.isArray()) {
                List<Integer> codes = new ArrayList<>();
                for (JsonNode c : codesNode) codes.add(c.intValue());
                api.setStatusCodes(codes);
            }

            // Parameters
            JsonNode paramsNode = apiNode.get("parameters");
            if (paramsNode != null && paramsNode.isArray()) {
                List<ApiParameter> params = new ArrayList<>();
                for (JsonNode pn : paramsNode) {
                    ApiParameter p = new ApiParameter();
                    p.setName(text(pn, "name"));
                    p.setType(text(pn, "type"));
                    p.setLocation(text(pn, "location"));
                    p.setRequired(pn.has("required") && pn.get("required").asBoolean());
                    params.add(p);
                }
                api.setParameters(params);
            }

            // Request body fields
            JsonNode reqFieldsNode = apiNode.get("requestBodyFields");
            if (reqFieldsNode != null && reqFieldsNode.isArray()) {
                api.setRequestBodyFields(parseFields(reqFieldsNode));
            }

            // Response body fields
            JsonNode respFieldsNode = apiNode.get("responseBodyFields");
            if (respFieldsNode != null && respFieldsNode.isArray()) {
                api.setResponseBodyFields(parseFields(respFieldsNode));
            }

            apis.add(api);
        }

        log.info("Tree-sitter parser extracted {} endpoints", apis.size());
        return apis;
    }

    private List<ApiField> parseFields(JsonNode fieldsNode) {
        List<ApiField> fields = new ArrayList<>();
        for (JsonNode fn : fieldsNode) {
            ApiField f = new ApiField();
            f.setName(text(fn, "name"));
            f.setType(text(fn, "type"));
            fields.add(f);
        }
        return fields;
    }

    private String text(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && !child.isNull()) ? child.asText() : null;
    }
}
