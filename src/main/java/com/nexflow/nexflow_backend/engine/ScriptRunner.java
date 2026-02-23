package com.nexflow.nexflow_backend.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs user-supplied scripts in a sandboxed subprocess.
 *
 * Supported languages:
 *   javascript — executed via `node`
 *   python     — executed via `python3`
 *
 * How it works:
 *   1. Write the NCO input data to a temp JSON file
 *   2. Wrap the user's code in a safe harness (error catching, result serialisation)
 *   3. Write the full script to a second temp file
 *   4. Run the subprocess, capture stdout as JSON
 *   5. Delete both temp files
 *   6. Return ScriptResult
 *
 * Timeout: 10 seconds. Process is force-killed on timeout.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptRunner {

    private static final int TIMEOUT_SECONDS = 10;

    private final ObjectMapper objectMapper;

    // ── Public API ────────────────────────────────────────────────────────────

    public ScriptResult run(String language, String userCode, Object inputData) {
        return switch (language.toLowerCase()) {
            case "javascript" -> runScript("js",  buildJsWrapper(userCode),  inputData, "node");
            case "python"     -> runScript("py",  buildPyWrapper(userCode),  inputData, "python3");
            default           -> ScriptResult.error("Unsupported language: " + language + ". Use 'javascript' or 'python'.");
        };
    }

    // ── Script wrappers ───────────────────────────────────────────────────────

    private String buildJsWrapper(String userCode) {
        return """
                const fs    = require('fs');
                const input = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));

                try {
                    const result = (function(input) {
                        %s
                    })(input);

                    process.stdout.write(JSON.stringify({ success: true, output: result ?? null }));
                } catch (e) {
                    process.stdout.write(JSON.stringify({ success: false, error: e.message }));
                }
                """.formatted(userCode);
    }

    private String buildPyWrapper(String userCode) {
        return """
                import json, sys

                with open(sys.argv[1]) as f:
                    input = json.load(f)

                try:
                %s
                    print(json.dumps({"success": True, "output": result}))
                except Exception as e:
                    print(json.dumps({"success": False, "error": str(e)}))
                """.formatted(indentPython(userCode));
    }

    // ── Core subprocess runner ────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ScriptResult runScript(String extension, String wrappedCode, Object inputData, String interpreter) {
        Path scriptFile = null;
        Path inputFile  = null;

        try {
            // Write input JSON
            inputFile  = Files.createTempFile("nf_input_",  ".json");
            scriptFile = Files.createTempFile("nf_script_", "." + extension);

            Files.writeString(inputFile,  objectMapper.writeValueAsString(inputData));
            Files.writeString(scriptFile, wrappedCode);

            // Run: node script.js input.json   OR   python3 script.py input.json
            Process process = new ProcessBuilder(interpreter, scriptFile.toString(), inputFile.toString())
                    .redirectErrorStream(false)
                    .start();

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return ScriptResult.error("Script timed out after " + TIMEOUT_SECONDS + " seconds. Check for infinite loops.");
            }

            String stdout = new String(process.getInputStream().readAllBytes()).trim();
            String stderr = new String(process.getErrorStream().readAllBytes()).trim();

            // Non-zero exit with no stdout = interpreter error (syntax error etc.)
            if (stdout.isEmpty()) {
                String errorMsg = stderr.isEmpty() ? "Script produced no output." : stderr;
                return ScriptResult.error(errorMsg);
            }

            // Parse the JSON result written by the wrapper
            Map<String, Object> parsed = objectMapper.readValue(stdout, Map.class);
            boolean success = Boolean.TRUE.equals(parsed.get("success"));

            if (success) {
                return ScriptResult.ok(parsed.get("output"));
            } else {
                return ScriptResult.error((String) parsed.getOrDefault("error", "Script returned failure."));
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ScriptResult.error("Script execution was interrupted.");
        } catch (IOException e) {
            log.error("ScriptRunner IO error: {}", e.getMessage());
            return ScriptResult.error("Failed to run script: " + e.getMessage());
        } finally {
            // Always clean up temp files
            deleteSilently(scriptFile);
            deleteSilently(inputFile);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Indent each line of user Python code by 4 spaces (goes inside try block) */
    private String indentPython(String code) {
        return code.lines()
                .map(line -> "    " + line)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private void deleteSilently(Path path) {
        if (path != null) {
            try { Files.deleteIfExists(path); }
            catch (IOException ignored) {}
        }
    }

    // ── Result type ───────────────────────────────────────────────────────────

    public record ScriptResult(boolean success, Object output, String error) {
        static ScriptResult ok(Object output)     { return new ScriptResult(true,  output, null); }
        static ScriptResult error(String message) { return new ScriptResult(false, null,   message); }
    }
}
