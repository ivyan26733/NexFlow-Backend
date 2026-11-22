package com.nexflow.nexflow_backend.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
 * Two-layer protection against runaway scripts:
 *
 *   Layer 1 — CPU watchdog (primary):
 *     A daemon thread polls the subprocess CPU usage every 2 seconds.
 *     If CPU stays above CPU_HIGH_THRESHOLD (90%) for CPU_HIGH_CONSECUTIVE_SAMPLES
 *     consecutive samples (default: 5 × 2s = 10 seconds of sustained high CPU),
 *     the process is force-killed and an "infinite loop detected" error is returned.
 *     This correctly distinguishes:
 *       - while(true) { a++ }    → CPU ~100% → killed in ~10s
 *       - await fetch(url)        → CPU ~0%   → runs freely
 *       - heavy but finite work   → CPU high but terminates on its own
 *
 *   Layer 2 — wall-clock timeout (safety net):
 *     A per-node configurable timeout (default 10s, max 300s) catches scripts that
 *     are stuck on I/O indefinitely (e.g. TCP connect to a host that never responds).
 *     Only fires if the CPU watchdog did not already kill the process.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptRunner {

    private static final int DEFAULT_TIMEOUT_SECONDS       = 10;
    private static final int MAX_TIMEOUT_SECONDS           = 300;   // hard cap regardless of node config

    /** CPU usage rate (0.0–1.0) above which a sample is considered "high". */
    private static final double CPU_HIGH_THRESHOLD         = 0.90;

    /** How many consecutive high-CPU samples before we declare an infinite loop. */
    private static final int CPU_HIGH_CONSECUTIVE_SAMPLES  = 5;

    /** Interval between CPU samples in milliseconds. */
    private static final long CPU_POLL_INTERVAL_MS         = 2_000;

    private final ObjectMapper objectMapper;

    // ── Blocked patterns — modules / built-ins that allow network/fs/process access ──

    private static final List<String> JS_BLOCKED = List.of(
            "require('child_process')", "require(\"child_process\")",
            "require('fs')",  "require(\"fs\")",
            "require('net')", "require(\"net\")",
            "require('http')", "require(\"http\")",
            "require('https')", "require(\"https\")",
            "require('os')", "require(\"os\")",
            "require('path')", "require(\"path\")",
            "require('crypto')", "require(\"crypto\")",
            "process.env", "process.exit", "process.kill",
            "globalThis.process", "__dirname", "__filename"
    );

    private static final List<String> PY_BLOCKED = List.of(
            "import subprocess", "import os", "import sys",
            "import socket", "import urllib", "import http",
            "import ftplib", "import smtplib",
            "__import__", "exec(", "eval(",
            "open(", "os.system", "os.popen"
    );

    // ── Public API ────────────────────────────────────────────────────────────

    public ScriptResult run(String language, String userCode, Object inputData) {
        return run(language, userCode, inputData, DEFAULT_TIMEOUT_SECONDS);
    }

    public ScriptResult run(String language, String userCode, Object inputData, int timeoutSeconds) {
        return run(language, userCode, inputData, timeoutSeconds, true);
    }

    /**
     * @param cpuWatchdog false for heavy but finite work (ML training, big data crunching) that
     *                    legitimately keeps a core busy; the wall-clock timeout still applies.
     */
    public ScriptResult run(String language, String userCode, Object inputData, int timeoutSeconds, boolean cpuWatchdog) {
        // Clamp the timeout so one bad node config cannot run forever.
        int timeout = Math.min(Math.max(timeoutSeconds, 1), MAX_TIMEOUT_SECONDS);
        return switch (language.toLowerCase()) {
            case "javascript" -> {
                String blocked = findBlockedPattern(userCode, JS_BLOCKED);
                if (blocked != null) yield ScriptResult.error("Script uses blocked pattern: " + blocked);
                yield runScript("js", buildJsWrapper(userCode), inputData, "node", timeout, cpuWatchdog);
            }
            case "python" -> {
                String blocked = findBlockedPattern(userCode, PY_BLOCKED);
                if (blocked != null) yield ScriptResult.error("Script uses blocked pattern: " + blocked);
                yield runScript("py", buildPyWrapper(userCode), inputData, "python3", timeout, cpuWatchdog);
            }
            default -> ScriptResult.error("Unsupported language: " + language + ". Use 'javascript' or 'python'.");
        };
    }

    private static String findBlockedPattern(String code, List<String> patterns) {
        for (String pattern : patterns) {
            if (code.contains(pattern)) return pattern;
        }
        return null;
    }

    // ── Script wrappers ───────────────────────────────────────────────────────

    private String buildJsWrapper(String userCode) {
        return """
                const fs    = require('fs');
                const _data = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
                // nex  — unified flat container: nex.userId, nex.fetchUser.body.items, etc.
                // input — legacy access: input.variables, input.nodes, input.trigger (backward compat)
                // Same shortcut as {{nex.step.field}}: a field missing on a step is looked up in its result / body.
                const _nfStep = v => (v && typeof v === 'object' && !Array.isArray(v)) ? new Proxy(v, {
                    get(t, k, r) {
                        if (typeof k !== 'string' || k in t) return Reflect.get(t, k, r);
                        for (const w of ['result', 'body']) {
                            const inner = t[w];
                            if (inner && typeof inner === 'object' && !Array.isArray(inner) && k in inner) return inner[k];
                        }
                        return undefined;
                    }
                }) : v;
                const _nfNex = _data.nex || {};
                const nex   = {};
                for (const k of Object.keys(_nfNex)) nex[k] = _nfStep(_nfNex[k]);
                const input = _data.input || {};

                try {
                    const result = (function(nex, input) {
                        %s
                    })(nex, input);

                    process.stdout.write(JSON.stringify({ success: true, output: result ?? null }));
                } catch (e) {
                    process.stdout.write(JSON.stringify({ success: false, error: e.message }));
                }
                """.formatted(userCode);
    }

    private String buildPyWrapper(String userCode) {
        return """
                import json, sys

                class _NexDict(dict):
                    # nex.fetchUser.body.email works as well as nex['fetchUser']['body']['email'].
                    # ndim/shape/dtype and _names stay plain attributes so pandas and numpy see a normal dict.
                    __slots__ = ()
                    def __getattr__(self, name):
                        if name.startswith('_') or name in ('ndim', 'shape', 'dtype'):
                            raise AttributeError(name)
                        try:
                            return self[name]
                        except KeyError:
                            have = ', '.join(sorted(str(k) for k in self)[:25]) or 'nothing'
                            raise AttributeError(f"no '{name}' here. Available: {have}") from None
                    def __setattr__(self, name, value):
                        self[name] = value
                    def __delattr__(self, name):
                        del self[name]

                class _NexStep(_NexDict):
                    # Same shortcut as {{nex.step.field}}: a field missing on a step is looked up in its result / body.
                    __slots__ = ()
                    def __missing__(self, key):
                        for w in ('result', 'body'):
                            inner = dict.get(self, w)
                            if isinstance(inner, dict) and key in inner:
                                return inner[key]
                        raise KeyError(key)
                    def get(self, key, default=None):
                        try:
                            return self[key]
                        except KeyError:
                            return default

                class _NexBoth:
                    # A field named like a dict method (items, values, keys...): nex.api.items[0] reads the data,
                    # nex.api.items() still calls the method.
                    __slots__ = ('_m', '_v')
                    def __init__(self, m, v):
                        object.__setattr__(self, '_m', m)
                        object.__setattr__(self, '_v', v)
                    def __call__(self, *a, **k): return self._m(*a, **k)
                    def __getitem__(self, i): return self._v[i]
                    def __iter__(self): return iter(self._v)
                    def __len__(self): return len(self._v)
                    def __contains__(self, x): return x in self._v
                    def __bool__(self): return bool(self._v)
                    def __eq__(self, o): return self._v == (o._v if isinstance(o, _NexBoth) else o)
                    __hash__ = None
                    def __repr__(self): return repr(self._v)
                    def __str__(self): return str(self._v)
                    def __getattr__(self, n): return getattr(self._v, n)

                class _NexField:
                    def __init__(self, name):
                        self.name = name
                    def __get__(self, obj, cls=None):
                        method = getattr(dict, self.name)
                        if obj is None:
                            return method
                        method = method.__get__(obj, cls)
                        try:
                            return _NexBoth(method, obj[self.name])
                        except KeyError:
                            return method

                for _n in ('items', 'keys', 'values', 'copy', 'update', 'pop', 'popitem', 'clear', 'setdefault'):
                    setattr(_NexDict, _n, _NexField(_n))

                with open(sys.argv[1]) as f:
                    _data = json.load(f, object_hook=_NexDict)
                # nex  — unified flat container: nex.userId, nex.fetchUser.body.items (or nex['userId'], ...)
                # input — legacy access: input['variables'], input['nodes'], input['trigger'] (backward compat)
                nex   = _NexDict({k: _NexStep(v) if isinstance(v, dict) else v for k, v in _data.get('nex', {}).items()})
                input = _data.get('input', _NexDict())

                def _nf_jsonable(o):
                    # Make the result valid JSON: NaN/inf -> null, numpy & pandas values -> plain Python.
                    if o is None or isinstance(o, (bool, str)):
                        return o
                    if type(o).__name__ == 'NAType':                      # pandas.NA
                        return None
                    if isinstance(o, float):
                        return o if o == o and o not in (float('inf'), float('-inf')) else None
                    if isinstance(o, int):
                        return o
                    if isinstance(o, _NexBoth):
                        return _nf_jsonable(o._v)
                    if isinstance(o, dict):
                        return {str(k): _nf_jsonable(v) for k, v in dict.items(o)}
                    if isinstance(o, (list, tuple, set)):
                        return [_nf_jsonable(v) for v in o]
                    if hasattr(o, 'to_dict') and hasattr(o, 'columns'):   # pandas DataFrame -> rows
                        return _nf_jsonable(o.to_dict('records'))
                    if hasattr(o, 'to_dict'):                             # pandas Series -> {index: value}
                        return _nf_jsonable(o.to_dict())
                    if hasattr(o, 'isoformat'):                           # dates / timestamps (NaT -> null)
                        try:
                            return None if o != o else o.isoformat()
                        except Exception:
                            return str(o)
                    if hasattr(o, 'tolist'):                              # numpy arrays and scalars
                        return _nf_jsonable(o.tolist())
                    return str(o)

                try:
                %s
                    print(json.dumps({"success": True, "output": _nf_jsonable(result)}, allow_nan=False))
                except Exception as e:
                    print(json.dumps({"success": False, "error": type(e).__name__ + ": " + str(e)}))
                """.formatted(indentPython(userCode));
    }

    // ── Core subprocess runner ────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ScriptResult runScript(String extension, String wrappedCode, Object inputData,
                                   String interpreter, int timeoutSeconds, boolean cpuWatchdog) {
        Path scriptFile = null;
        Path inputFile  = null;

        try {
            inputFile  = Files.createTempFile("nf_input_",  ".json");
            scriptFile = Files.createTempFile("nf_script_", "." + extension);

            Files.writeString(inputFile,  objectMapper.writeValueAsString(inputData));
            Files.writeString(scriptFile, wrappedCode);

            Process process = new ProcessBuilder(interpreter, scriptFile.toString(), inputFile.toString())
                    .redirectErrorStream(false)
                    .start();

            // ── Drain stdout + stderr concurrently ────────────────────────────
            // IMPORTANT: must drain both streams in background threads BEFORE calling
            // waitFor(). If the script writes more than the OS pipe buffer (~64 KB on
            // Linux) before the JVM reads, the script blocks on write() and the JVM
            // blocks in waitFor() — deadlock, always hitting the wall-clock timeout.
            AtomicReference<byte[]> stdoutBytes = new AtomicReference<>(new byte[0]);
            AtomicReference<byte[]> stderrBytes = new AtomicReference<>(new byte[0]);

            Thread stdoutDrainer = drainStream(process.getInputStream(), stdoutBytes);
            Thread stderrDrainer = drainStream(process.getErrorStream(), stderrBytes);

            // ── Layer 1: CPU watchdog ─────────────────────────────────────────
            AtomicBoolean infiniteLoopKilled = new AtomicBoolean(false);
            Thread watchdog = cpuWatchdog ? startCpuWatchdog(process, infiniteLoopKilled) : null;
            // The watchdog watches CPU usage. The waitFor() call below is the wall-clock timeout.

            // ── Layer 2: wall-clock timeout ───────────────────────────────────
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (watchdog != null) watchdog.interrupt(); // stop watchdog — process is done or timed out

            // Wait for drainer threads so stdout/stderr are fully read before we use them
            stdoutDrainer.join(5_000);
            stderrDrainer.join(5_000);

            // Check watchdog result first — it killed the process before timeout fired
            if (infiniteLoopKilled.get()) {
                return ScriptResult.error(
                    "Infinite loop detected: script consumed 100% CPU for " +
                    (CPU_HIGH_CONSECUTIVE_SAMPLES * CPU_POLL_INTERVAL_MS / 1000) +
                    " seconds continuously. Use a loop with a termination condition, or turn on " +
                    "\"Heavy computation\" in the step's settings if the work is long but finite (e.g. ML training)."
                );
            }

            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return ScriptResult.error(
                    "Script timed out after " + timeoutSeconds + " seconds. " +
                    "The script may be waiting on a network call or external resource that never responded."
                );
            }

            String stdout = new String(stdoutBytes.get()).trim();
            String stderr = new String(stderrBytes.get()).trim();

            if (stdout.isEmpty()) {
                String errorMsg = stderr.isEmpty() ? "Script produced no output." : stderr;
                return ScriptResult.error(errorMsg);
            }

            // Parse only the JSON object: from the first '{' to the end.
            // (script may have console.log'd before the wrapper writes JSON)
            int jsonStart = stdout.indexOf('{');
            if (jsonStart == -1) {
                return ScriptResult.error("Script produced no valid JSON. Output: " + truncate(stdout, 200));
            }
            String jsonStr = stdout.substring(jsonStart);
            Map<String, Object> parsed = objectMapper.readValue(jsonStr, Map.class);
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
            deleteSilently(scriptFile);
            deleteSilently(inputFile);
        }
    }

    // ── CPU watchdog ─────────────────────────────────────────────────────────

    /**
     * Spawns a daemon thread that monitors the subprocess CPU usage.
     *
     * Every CPU_POLL_INTERVAL_MS milliseconds it reads the process's total CPU time
     * via ProcessHandle and computes the CPU rate for that interval:
     *
     *   cpuRate = (cpuTimeUsedThisInterval) / (wallTimeElapsedThisInterval)
     *
     * A cpuRate of 1.0 means the process used 100% of one CPU core for the full interval.
     * If cpuRate exceeds CPU_HIGH_THRESHOLD for CPU_HIGH_CONSECUTIVE_SAMPLES consecutive
     * polls, the process is force-killed and infiniteLoopKilled is set to true.
     *
     * The watchdog stops cleanly when its thread is interrupted (called after waitFor returns).
     *
     * Fallback: if the OS does not support ProcessHandle CPU reporting (totalCpuDuration
     * returns empty), the watchdog exits silently and the wall-clock timeout acts as the
     * only protection — no false positives.
     */
    private Thread startCpuWatchdog(Process process, AtomicBoolean infiniteLoopKilled) {
        Thread watchdog = new Thread(() -> {
            ProcessHandle handle = process.toHandle();

            // Establish baseline before first poll interval
            long lastCpuNanos  = getCpuNanos(handle);
            long lastWallNanos = System.nanoTime();

            // -1 signals that ProcessHandle CPU reporting is unsupported on this OS
            if (lastCpuNanos < 0) {
                log.debug("[CpuWatchdog] ProcessHandle CPU duration unavailable — watchdog inactive, wall-clock timeout only.");
                return;
            }

            int consecutiveHighSamples = 0;

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(CPU_POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                if (!handle.isAlive()) break;

                long currentCpuNanos  = getCpuNanos(handle);
                long currentWallNanos = System.nanoTime();

                if (currentCpuNanos < 0) break; // CPU reporting went away

                long cpuDelta  = currentCpuNanos  - lastCpuNanos;
                long wallDelta = currentWallNanos  - lastWallNanos;

                lastCpuNanos  = currentCpuNanos;
                lastWallNanos = currentWallNanos;

                if (wallDelta <= 0) continue;

                double cpuRate = (double) cpuDelta / wallDelta;

                if (cpuRate >= CPU_HIGH_THRESHOLD) {
                    consecutiveHighSamples++;
                    // One sample is not enough. We only kill after sustained high CPU.
                    log.debug("[CpuWatchdog] High CPU sample #{} — rate={:.0f}%%", consecutiveHighSamples, cpuRate * 100);
                } else {
                    consecutiveHighSamples = 0; // reset — CPU dropped, not an infinite loop
                }

                if (consecutiveHighSamples >= CPU_HIGH_CONSECUTIVE_SAMPLES) {
                    log.warn("[CpuWatchdog] Infinite loop detected — {} consecutive high-CPU samples. Killing process.",
                            consecutiveHighSamples);
                    infiniteLoopKilled.set(true);
                    process.destroyForcibly();
                    break;
                }
            }
        });

        watchdog.setDaemon(true);
        watchdog.setName("script-cpu-watchdog");
        watchdog.start();
        return watchdog;
    }

    /**
     * Returns the total CPU time consumed by the process in nanoseconds,
     * or -1 if not available on this platform.
     */
    private static long getCpuNanos(ProcessHandle handle) {
        return handle.info()
                     .totalCpuDuration()
                     .map(Duration::toNanos)
                     .orElse(-1L);
    }

    /**
     * Spawns a daemon thread that reads the given InputStream fully into a byte array.
     * The result is stored in the provided AtomicReference.
     *
     * Why: OS pipe buffers are typically 64 KB on Linux. If a script writes more than
     * that to stdout before the JVM reads it, the script blocks on write() while the
     * JVM is blocked in waitFor() — classic deadlock. Draining in a background thread
     * prevents this regardless of output size.
     */
    private Thread drainStream(InputStream stream, AtomicReference<byte[]> target) {
        Thread t = new Thread(() -> {
            try {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = stream.read(chunk)) != -1) {
                    buf.write(chunk, 0, n);
                }
                target.set(buf.toByteArray());
            } catch (IOException ignored) {
                // stream closed early (process killed) — use whatever was collected
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ── Result type ───────────────────────────────────────────────────────────

    public record ScriptResult(boolean success, Object output, String error) {
        static ScriptResult ok(Object output)     { return new ScriptResult(true,  output, null); }
        static ScriptResult error(String message) { return new ScriptResult(false, null,   message); }
    }
}
