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

    private static final Map<String, Object> NEX_DATA = Map.of("nex", Map.of(
            "calc", Map.of("result", Map.of("total", 5), "language", "python"),
            "api", Map.of("statusCode", 200, "body", Map.of("email", "a@b.c", "items", List.of(Map.of("x", 1)))),
            "amount", 7));

    @Test
    void pythonReadsNexWithDotsOrBrackets() {
        ScriptRunner.ScriptResult r = runner.run("python",
                "result = [nex.calc.total, nex.calc.result.total, nex['calc']['total'], nex.api.email, "
                        + "nex.api.body['items'][0].x, nex.amount, nex.calc.language, nex.get('calc').get('result').get('total'), "
                        + "nex.get('api', {}).get('email'), nex.calc.get('nope', 0)]",
                NEX_DATA, 10);

        assertTrue(r.success(), r.error());
        assertEquals(List.of(5, 5, 5, "a@b.c", 1, 7, "python", 5, "a@b.c", 0), r.output());
    }

    @Test
    void pythonFieldsNamedLikeDictMethodsReadTheData() {
        ScriptRunner.ScriptResult r = runner.run("python",
                "result = [nex.api.items[0].x, len(nex.api.body.items), sorted(k for k, v in nex.api.body.items()), "
                        + "json.loads(json.dumps(nex.api))['body']['items'][0]['x'], nex.api.items]",
                NEX_DATA, 10);

        assertTrue(r.success(), r.error());
        assertEquals(List.of(1, 1, List.of("email", "items"), 1, List.of(Map.of("x", 1))), r.output());
    }

    @Test
    void pythonMissingFieldSaysWhatIsThere() {
        ScriptRunner.ScriptResult r = runner.run("python", "result = nex.calc.totl", NEX_DATA, 10);

        assertFalse(r.success());
        assertTrue(r.error().contains("no 'totl' here. Available: language, result"), r.error());
    }

    @Test
    void javascriptStepFieldsWorkWithoutResultOrBody() {
        Assumptions.assumeTrue(hasNode(), "node not installed");
        ScriptRunner.ScriptResult r = runner.run("javascript",
                "return [nex.calc.total, nex.calc.result.total, nex.api.email, nex.api.items[0].x, nex.amount, nex.calc.language, nex.calc.nope ?? null]",
                NEX_DATA, 10);

        assertTrue(r.success(), r.error());
        assertEquals(java.util.Arrays.asList(5, 5, "a@b.c", 1, 7, "python", null), r.output());
    }

    private static boolean hasNode() {
        try {
            return new ProcessBuilder("node", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
