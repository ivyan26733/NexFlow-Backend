package com.nexflow.nexflow_backend.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScriptRunnerTest {

    private final ScriptRunner runner = new ScriptRunner(new ObjectMapper());

    @BeforeAll
    static void needsPython() {
        boolean available;
        try {
            available = new ProcessBuilder("python3", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            available = false;
        }
        Assumptions.assumeTrue(available, "python3 not installed");
    }

    @Test
    void pythonNanAndInfinityBecomeNull() {
        ScriptRunner.ScriptResult r = runner.run("python",
                "result = {'nan': float('nan'), 'inf': float('inf'), 'ok': 1.5, 'rows': [{'x': float('nan')}]}",
                Map.of(), 10);

        assertTrue(r.success(), r.error());
        Map<?, ?> out = (Map<?, ?>) r.output();
        assertNull(out.get("nan"));
        assertNull(out.get("inf"));
        assertEquals(1.5, out.get("ok"));
        assertNull(((Map<?, ?>) ((List<?>) out.get("rows")).get(0)).get("x"));
    }

    @Test
    void pythonSetsAndTuplesBecomeLists() {
        ScriptRunner.ScriptResult r = runner.run("python", "result = {'t': (1, 2), 's': {3}}", Map.of(), 10);

        assertTrue(r.success(), r.error());
        Map<?, ?> out = (Map<?, ?>) r.output();
        assertEquals(List.of(1, 2), out.get("t"));
        assertEquals(List.of(3), out.get("s"));
    }

    @Test
    void pythonErrorNamesTheExceptionType() {
        ScriptRunner.ScriptResult r = runner.run("python", "result = {}['missing']", Map.of(), 10);

        assertFalse(r.success());
        assertTrue(r.error().startsWith("KeyError"), r.error());
    }

    @Test
    void heavyComputeStillHonoursTheTimeout() {
        ScriptRunner.ScriptResult r = runner.run("python", "while True:\n    pass", Map.of(), 2, false);

        assertFalse(r.success());
        assertTrue(r.error().contains("timed out"), r.error());
    }
}
