package com.nexflow.nexflow_backend.executor;

import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReferenceResolverTest {

    private final ReferenceResolver resolver = new ReferenceResolver();
    private NexflowContextObject nco;

    @BeforeEach
    void setUp() {
        nco = NexflowContextObject.create(UUID.randomUUID().toString(), "exec-1", null);
        Map<String, Object> nex = nco.getNex();
        nex.put("start", Map.of("body", Map.of("orderId", "SO-1", "qty", 3)));
        nex.put("orderId", "SO-1");
        nex.put("calc", Map.of("result", Map.of("total", 120, "items", List.of("a", "b")), "language", "python"));
        nex.put("fetchUser", Map.of("statusCode", 200, "body", Map.of("email", "a@b.c", "tags", List.of(List.of("x", "y")))));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("Customer Name", "Apex Foods");
        row.put("unit.price", 9.5);
        nex.put("readFile", Map.of("rows", List.of(row), "rowCount", 1));
        nex.put("price", "Rs $500 \\ net");
        nco.setVariable("amount", 42);
        nco.setNodeAlias("calc", NodeContext.builder().nodeType("SCRIPT")
                .successOutput(new HashMap<>(Map.of("result", Map.of("total", 120)))).build());
    }

    private Object ref(String path) {
        return resolver.resolveToObject(path, nco);
    }

    @Test
    void everyOldFormStillWorks() {
        assertEquals("120", resolver.resolve("{{nex.calc.result.total}}", nco));
        assertEquals("120", resolver.resolve("{{ nex.calc.result.total }}", nco));
        assertEquals("120", resolver.resolve("{{nodes.calc.successOutput.result.total}}", nco));
        assertEquals("42", resolver.resolve("{{variables.amount}}", nco));
        assertEquals("SO-1", resolver.resolve("{{nex.start.body.orderId}}", nco));
        assertEquals("a", ref("nex.calc.result.items.0"));
        assertEquals("a", ref("nex.calc.result.items[0]"));
        assertEquals("exec-1", resolver.resolve("{{meta.executionId}}", nco));
        assertEquals("43", resolver.resolve("{{variables.amount + 1}}", nco));
        assertEquals("360", resolver.resolve("{{nex.calc.total * 3}}", nco));
        assertEquals("117.5", resolver.resolve("{{nex.calc.total - 2.5}}", nco));
        assertEquals("Order SO-1 is 120", resolver.resolve("Order {{nex.orderId}} is {{nex.calc.result.total}}", nco));
        assertEquals(120, resolver.resolveMap(Map.of("v", "nex.calc.result.total"), nco).get("v"));
    }

    @Test
    void stepFieldsWorkWithoutResultOrBody() {
        assertEquals(120, ref("nex.calc.total"));
        assertEquals("b", ref("nex.calc.items[1]"));
        assertEquals("a@b.c", ref("nex.fetchUser.email"));
        assertEquals("SO-1", ref("nex.start.orderId"));
        // The step's own fields come first
        assertEquals("python", ref("nex.calc.language"));
        assertEquals(200, ref("nex.fetchUser.statusCode"));
        assertNull(ref("nex.calc.missing"));
        // Only one level: deeper paths are exact
        assertNull(ref("nex.fetchUser.body.missing.email"));
    }

    @Test
    void listsAndKeysWithSpaces() {
        assertEquals("y", ref("nex.fetchUser.body.tags[0][1]"));
        assertEquals("Apex Foods", ref("nex.readFile.rows[0][\"Customer Name\"]"));
        assertEquals("Apex Foods", ref("nex.readFile.rows[0]['Customer Name']"));
        assertEquals("Apex Foods", ref("nex.readFile.rows[0].Customer Name"));
        assertEquals(9.5, ref("nex.readFile.rows[0][\"unit.price\"]"));
        assertEquals("SO-1", ref("nex[\"orderId\"]"));
        assertEquals("Apex Foods", resolver.resolve("Hi {{nex.readFile.rows[0][\"Customer Name\"]}}", nco).substring(3));
    }

    @Test
    void badPathsGiveNothingInsteadOfFailing() {
        for (String p : List.of("nex.calc.items[9]", "nex.calc.items[x]", "nex.calc.items[99999999999]", "nex.calc..total",
                "nex.readFile.rows[0][\"Customer Name\"", "nex.readFile.rows[0]x", "nex.", "nex.orderId[0]",
                "nex.readFile.rows[\"0\"]")) {
            assertNull(ref(p), p);
        }
        assertEquals("", resolver.resolve("{{nex.nothingHere}}", nco));
        assertEquals("", resolver.resolve("{{nex.calc.total - 99999999999999999999}}", nco));
    }

    @Test
    void singleFieldsWorkWithoutBraces() {
        assertEquals("120", resolver.resolve("nex.calc.total", nco));
        assertEquals("120", resolver.resolve("  nex.calc.result.total ", nco));
        assertEquals("42", resolver.resolve("variables.amount", nco));
        assertEquals("{\"total\":120,\"items\":[\"a\",\"b\"]}".length(), resolver.resolveForText("nex.calc.result", nco).length());
        assertTrue(ReferenceResolver.isSingleReference("nex.file"));
        assertTrue(ReferenceResolver.isSingleReference("{{nex.file}}"));
        assertFalse(ReferenceResolver.isSingleReference("{{nex.a}} and {{nex.b}}"));
    }

    @Test
    void plainTextIsNeverTreatedAsAReference() {
        // Looks like a path but nothing is there: kept exactly as typed
        assertEquals("input.csv", resolver.resolve("input.csv", nco));
        assertEquals("meta.json", resolver.resolve("meta.json", nco));
        assertEquals("nex.pdf", resolver.resolve("nex.pdf", nco));
        assertEquals("report.xlsx", resolver.resolve("report.xlsx", nco));
        // Text around a path is not a reference
        assertEquals("Total: nex.calc.total", resolver.resolve("Total: nex.calc.total", nco));
        assertEquals("nex.calc.total is due", resolver.resolveForText("nex.calc.total is due", nco));
        assertEquals("https://api.example.com/v1", resolver.resolve("https://api.example.com/v1", nco));
        assertEquals("500", resolver.resolve("500", nco));
        assertEquals("{loop.index} < 3", resolver.resolve("{loop.index} < 3", nco));
        assertNull(resolver.resolve(null, nco));
    }

    @Test
    void valuesWithDollarOrBackslashAreFilledInSafely() {
        assertEquals("Price: Rs $500 \\ net", resolver.resolve("Price: {{nex.price}}", nco));
        assertEquals("Price: Rs $500 \\ net", resolver.resolveForText("Price: {{nex.price}}", nco));
        assertEquals("Rs $500 \\ net", resolver.resolve("nex.price", nco));
    }
}
