package com.nexflow.nexflow_backend.executor;

import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DataPathsTest {

    private final ReferenceResolver resolver = new ReferenceResolver();

    private static Map<String, Object> sampleNex() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("order_id", "SO-1");
        row.put("Customer Name", "Apex Foods");
        row.put("2024", 7);
        row.put("it's \"odd\"", 1);
        Map<String, Object> nex = new LinkedHashMap<>();
        nex.put("start", Map.of("body", Map.of("orderId", "SO-1")));
        nex.put("orderId", "SO-1");
        nex.put("calc", Map.of("result", Map.of("total", 120, "language", "inner", "lines", List.of(Map.of("sku", "A"))),
                "language", "python"));
        nex.put("fetchUser", Map.of("statusCode", 200, "body", Map.of("email", "a@b.c", "total", 9)));
        nex.put("both", Map.of("result", Map.of("x", 1), "body", Map.of("x", 2, "y", 3)));
        nex.put("readFile", Map.of("rows", List.of(row), "rowCount", 1));
        nex.put("tags", List.of("a", "b"));
        return nex;
    }

    @Test
    void everyListedPathResolvesToTheValueShown() {
        Map<String, Object> nex = sampleNex();
        NexflowContextObject nco = NexflowContextObject.create(UUID.randomUUID().toString(), "e", null);
        nco.getNex().putAll(nex);

        List<DataPaths.Group> groups = DataPaths.describe(nex);
        assertEquals(nex.size(), groups.size());
        int checked = 0;
        for (DataPaths.Group g : groups) {
            for (DataPaths.Field f : g.fields()) {
                Object v = resolver.resolveToObject(f.path(), nco);
                assertNotNull(v, f.path());
                assertEquals(String.valueOf(v), resolver.resolve("{{" + f.path() + "}}", nco), f.path());
                if (!(v instanceof Map) && !(v instanceof List)) assertEquals(String.valueOf(v), f.preview(), f.path());
                checked++;
            }
        }
        assertTrue(checked > 20, "checked " + checked);
    }

    @Test
    void usesTheShortcutOnlyWhereItGivesTheSameValue() {
        List<String> paths = DataPaths.describe(sampleNex()).stream()
                .flatMap(g -> g.fields().stream()).map(DataPaths.Field::path).toList();

        assertTrue(paths.contains("nex.calc.total"));
        assertTrue(paths.contains("nex.calc.lines[0].sku"));
        assertTrue(paths.contains("nex.calc.language"));          // the step's own field
        assertTrue(paths.contains("nex.calc.result.language"));   // hidden by it, so written in full
        assertTrue(paths.contains("nex.fetchUser.email"));
        assertTrue(paths.contains("nex.both.x"));                 // result wins…
        assertTrue(paths.contains("nex.both.body.x"));            // …so body's x is written in full
        assertTrue(paths.contains("nex.both.y"));
        assertTrue(paths.contains("nex.readFile.rows[0][\"Customer Name\"]"));
        assertTrue(paths.contains("nex.readFile.rows[0][\"2024\"]"));
        assertTrue(paths.contains("nex.tags[0]"));
        assertFalse(paths.stream().anyMatch(p -> p.contains("odd")));  // unwritable key is left out
    }
}
