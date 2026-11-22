package com.nexflow.nexflow_backend.executor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Lists what a run left in nex as ready-to-use references, for Studio's "Insert data" picker:
 * nex.calc.total (number) 120, nex.readFile.rows[0]["Customer Name"] (text) "Apex Foods"…
 * Paths use the step shortcut (no .result / .body) whenever {@link ReferenceResolver} resolves it to
 * the same value, so what the picker shows is exactly what works in a field.
 */
public final class DataPaths {

    private static final Pattern SIMPLE_KEY = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final int MAX_DEPTH = 5;
    private static final int MAX_PER_GROUP = 80;
    private static final int MAX_TOTAL = 600;
    private static final int PREVIEW_CHARS = 60;

    public record Field(String path, String type, String preview) {}
    public record Group(String key, List<Field> fields, boolean truncated) {}

    private DataPaths() {}

    public static List<Group> describe(Map<String, Object> nex) {
        List<Group> groups = new ArrayList<>();
        if (nex == null) return groups;
        int[] total = { 0 };
        for (Map.Entry<String, Object> e : nex.entrySet()) {
            if (total[0] >= MAX_TOTAL) break;
            String root = "nex" + segment(e.getKey());
            if (root == null) continue;
            List<Field> fields = new ArrayList<>();
            boolean[] cut = { false };
            Object value = e.getValue();
            add(fields, root, value, cut, total);
            if (value instanceof Map<?, ?> step) {
                // A step's own fields first (statusCode, language…), then what is inside result / body.
                Map<String, Object> shortcut = new LinkedHashMap<>();
                for (String wrapper : new String[] { "result", "body" }) {
                    if (step.get(wrapper) instanceof Map<?, ?> inner) {
                        inner.forEach((k, v) -> {
                            if (!step.containsKey(k)) shortcut.putIfAbsent(String.valueOf(k), v);
                        });
                    }
                }
                for (Map.Entry<?, ?> f : step.entrySet()) {
                    String key = String.valueOf(f.getKey());
                    Object v = f.getValue();
                    boolean wrapperShown = (key.equals("result") || key.equals("body")) && v instanceof Map<?, ?> m && !m.isEmpty();
                    if (wrapperShown) {
                        String wrapperPath = root + segment(key);
                        add(fields, wrapperPath, v, cut, total);   // the whole result / body
                        // Fields the shortcut cannot reach (hidden by a step field, or body fields also in result)
                        for (Map.Entry<?, ?> inner : ((Map<?, ?>) v).entrySet()) {
                            String k = String.valueOf(inner.getKey());
                            if (shortcut.get(k) != inner.getValue() || !shortcut.containsKey(k)) {
                                walk(fields, path(wrapperPath, k), inner.getValue(), 2, cut, total);
                            }
                        }
                        continue;
                    }
                    walk(fields, path(root, key), v, 1, cut, total);
                }
                shortcut.forEach((k, v) -> walk(fields, path(root, k), v, 1, cut, total));
            } else if (value instanceof List<?> list && !list.isEmpty()) {
                walk(fields, root + "[0]", list.get(0), 1, cut, total);
            }
            groups.add(new Group(e.getKey(), fields, cut[0]));
        }
        return groups;
    }

    private static void walk(List<Field> out, String path, Object value, int depth, boolean[] cut, int[] total) {
        if (path == null) return;
        if (!add(out, path, value, cut, total)) return;
        if (depth >= MAX_DEPTH) return;
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> f : map.entrySet()) walk(out, path(path, String.valueOf(f.getKey())), f.getValue(), depth + 1, cut, total);
        } else if (value instanceof List<?> list && !list.isEmpty()) {
            walk(out, path + "[0]", list.get(0), depth + 1, cut, total);
        }
    }

    private static boolean add(List<Field> out, String path, Object value, boolean[] cut, int[] total) {
        if (out.size() >= MAX_PER_GROUP || total[0] >= MAX_TOTAL) {
            cut[0] = true;
            return false;
        }
        out.add(new Field(path, type(value), preview(value)));
        total[0]++;
        return true;
    }

    private static String path(String parent, String key) {
        String seg = segment(key);
        return parent == null || seg == null ? null : parent + seg;
    }

    /** ".name" for plain names, ["Customer Name"] for anything else; null if it cannot be written. */
    static String segment(String key) {
        if (key == null || key.isEmpty() || key.contains("}")) return null;
        if (SIMPLE_KEY.matcher(key).matches()) return "." + key;
        if (!key.contains("\"")) return "[\"" + key + "\"]";
        if (!key.contains("'")) return "['" + key + "']";
        return null;
    }

    private static String type(Object v) {
        if (v == null) return "empty";
        if (v instanceof Map<?, ?>) return "object";
        if (v instanceof List<?>) return "list";
        if (v instanceof Number) return "number";
        if (v instanceof Boolean) return "yes/no";
        return "text";
    }

    private static String preview(Object v) {
        if (v == null) return "";
        if (v instanceof Map<?, ?> m) return m.size() + (m.size() == 1 ? " field" : " fields");
        if (v instanceof List<?> l) return l.size() + (l.size() == 1 ? " item" : " items");
        String s = String.valueOf(v).replaceAll("\\s+", " ");
        return s.length() > PREVIEW_CHARS ? s.substring(0, PREVIEW_CHARS) + "…" : s;
    }
}
