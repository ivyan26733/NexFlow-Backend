package com.nexflow.nexflow_backend.executor.file;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexflow.nexflow_backend.service.FileStorageService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reading and writing the file formats the FILE node supports. Pure functions — no storage, no NCO —
 * so they can be tested on their own.
 *
 * Read:  TEXT, CSV, JSON, EXCEL (.xlsx/.xls), PDF, WORD (.docx)
 * Write: CSV, EXCEL, JSON, TEXT, MARKDOWN, HTML, PDF, WORD
 */
public final class FileFormats {

    /** Caps keep a huge upload from bloating the run's saved context. */
    public static final int MAX_ROWS = 10_000;
    public static final int MAX_TEXT_CHARS = 200_000;

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private FileFormats() {}

    /** One output column: its header, and the dot path of the value inside each row (defaults to the header). */
    public record Column(String header, String path) {
        public String key() {
            return path != null && !path.isBlank() ? path.trim() : header;
        }
    }

    /** Bytes of a generated file plus how to label it. */
    public record Written(byte[] bytes, String mimeType, String extension, int rowCount) {}

    // ══ READ ═════════════════════════════════════════════════════════════════

    /** The format to parse a file as, from its extension / MIME type. */
    public static String detectFormat(String name, String mimeType) {
        String ext = FileStorageService.extension(name);
        String mime = mimeType != null ? mimeType.toLowerCase(Locale.ROOT) : "";
        if (ext.equals("csv") || ext.equals("tsv") || mime.contains("csv")) return "CSV";
        if (ext.equals("json") || mime.contains("json")) return "JSON";
        if (ext.equals("xlsx") || ext.equals("xls") || mime.contains("spreadsheet") || mime.contains("excel")) return "EXCEL";
        if (ext.equals("pdf") || mime.contains("pdf")) return "PDF";
        if (ext.equals("docx") || mime.contains("wordprocessingml")) return "WORD";
        if (mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/")) return "BINARY";
        return "TEXT";
    }

    /**
     * Parses {@code bytes}. {@code format} is one of the read formats or AUTO.
     * {@code delimiter} (CSV) and {@code sheet} (EXCEL: name or 1-based number) are optional.
     */
    public static Map<String, Object> read(byte[] bytes, String name, String mimeType,
                                           String format, String delimiter, String sheet) throws IOException {
        String fmt = format == null || format.isBlank() || format.equalsIgnoreCase("AUTO")
                ? detectFormat(name, mimeType) : format.toUpperCase(Locale.ROOT);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", fmt);
        switch (fmt) {
            case "CSV" -> {
                String text = decode(bytes);
                String delim = delimiter != null && !delimiter.isEmpty() ? unescape(delimiter)
                        : FileStorageService.extension(name).equals("tsv") ? "\t" : guessDelimiter(text);
                putTable(out, parseCsv(text, delim.charAt(0)));
            }
            case "JSON" -> {
                Object data = JSON.readValue(decode(bytes), Object.class);
                out.put("data", data);
                if (data instanceof List<?> list) out.put("rowCount", list.size());
            }
            case "EXCEL" -> readExcel(bytes, sheet, out);
            case "PDF" -> {
                try (PDDocument doc = Loader.loadPDF(bytes)) {
                    out.put("pages", doc.getNumberOfPages());
                    putText(out, new PDFTextStripper().getText(doc));
                }
            }
            case "WORD" -> {
                try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes));
                     XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
                    out.put("paragraphs", doc.getParagraphs().size());
                    putText(out, extractor.getText());
                }
            }
            case "BINARY" -> out.put("note", "This file type has no text to read. Use its fileId or downloadUrl in later steps.");
            default -> putText(out, decode(bytes));
        }
        return out;
    }

    private static void putText(Map<String, Object> out, String text) {
        String t = text != null ? text.replace("\r\n", "\n") : "";
        out.put("charCount", t.length());
        out.put("lineCount", t.isEmpty() ? 0 : t.split("\n", -1).length);
        if (t.length() > MAX_TEXT_CHARS) {
            out.put("text", t.substring(0, MAX_TEXT_CHARS));
            out.put("truncated", true);
        } else {
            out.put("text", t);
        }
    }

    /** rows / columns / rowCount from a header-first table. */
    private static void putTable(Map<String, Object> out, List<List<String>> table) {
        if (table.isEmpty()) {
            out.put("columns", List.of());
            out.put("rows", List.of());
            out.put("rowCount", 0);
            return;
        }
        List<String> headers = uniqueHeaders(table.get(0));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i < table.size() && rows.size() < MAX_ROWS; i++) {
            List<String> cells = table.get(i);
            if (cells.stream().allMatch(c -> c == null || c.isBlank())) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                row.put(headers.get(c), typed(c < cells.size() ? cells.get(c) : ""));
            }
            rows.add(row);
        }
        out.put("columns", headers);
        out.put("rows", rows);
        out.put("rowCount", rows.size());
        if (table.size() - 1 > MAX_ROWS) out.put("truncated", true);
    }

    private static List<String> uniqueHeaders(List<String> raw) {
        List<String> headers = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            String h = raw.get(i) != null ? raw.get(i).trim().replace("\uFEFF", "") : "";
            if (h.isEmpty()) h = "column" + (i + 1);
            String unique = h;
            for (int n = 2; !seen.add(unique); n++) unique = h + "_" + n;
            headers.add(unique);
        }
        return headers;
    }

    /** "42" → 42, "3.5" → 3.5; keeps codes like "007" or long IDs as text. */
    static Object typed(String value) {
        if (value == null) return "";
        String t = value.trim();
        if (t.isEmpty() || t.length() > 15) return value;
        if (t.matches("-?(0|[1-9]\\d*)")) return Long.parseLong(t);
        if (t.matches("-?(0|[1-9]\\d*)\\.\\d+")) return Double.parseDouble(t);
        if (t.equalsIgnoreCase("true") || t.equalsIgnoreCase("false")) return Boolean.parseBoolean(t);
        return value;
    }

    /** RFC 4180 CSV: quoted fields, "" escapes, newlines inside quotes. */
    public static List<List<String>> parseCsv(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int i = 0;
        int n = text.length();
        if (n > 0 && text.charAt(0) == '\uFEFF') i = 1; // BOM
        for (; i < n; i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.length() == 0) {
                quoted = true;
            } else if (c == delimiter) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                if (rows.size() > MAX_ROWS + 1) return rows;
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    static String guessDelimiter(String text) {
        int end = text.indexOf('\n');
        String first = end >= 0 ? text.substring(0, end) : text;
        char best = ',';
        long bestCount = 0;
        for (char d : new char[] { ',', ';', '\t', '|' }) {
            long count = first.chars().filter(ch -> ch == d).count();
            if (count > bestCount) {
                best = d;
                bestCount = count;
            }
        }
        return String.valueOf(best);
    }

    private static void readExcel(byte[] bytes, String sheetRef, Map<String, Object> out) throws IOException {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) names.add(wb.getSheetName(i));
            out.put("sheets", names);
            Sheet sheet = pickSheet(wb, sheetRef);
            out.put("sheet", sheet.getSheetName());

            DataFormatter formatter = new DataFormatter();
            List<List<String>> table = new ArrayList<>();
            int lastRow = Math.min(sheet.getLastRowNum(), MAX_ROWS + 1);
            for (int r = sheet.getFirstRowNum(); r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                List<String> cells = new ArrayList<>();
                if (row != null) {
                    for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++) {
                        cells.add(cellText(row.getCell(c), formatter));
                    }
                }
                if (table.isEmpty() && cells.stream().allMatch(String::isBlank)) continue; // skip leading blank rows
                table.add(cells);
            }
            putTable(out, table);
        }
    }

    private static Sheet pickSheet(Workbook wb, String ref) {
        if (ref != null && !ref.isBlank()) {
            Sheet byName = wb.getSheet(ref.trim());
            if (byName != null) return byName;
            if (ref.trim().matches("\\d+")) {
                int index = Integer.parseInt(ref.trim()) - 1;
                if (index >= 0 && index < wb.getNumberOfSheets()) return wb.getSheetAt(index);
            }
            throw new IllegalArgumentException("The workbook has no sheet '" + ref + "'.");
        }
        return wb.getSheetAt(0);
    }

    private static String cellText(Cell cell, DataFormatter formatter) {
        if (cell == null) return "";
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.NUMERIC && !DateUtil.isCellDateFormatted(cell)) {
            double v = cell.getNumericCellValue();
            return v == Math.rint(v) && Math.abs(v) < 1e15 ? String.valueOf((long) v) : String.valueOf(v);
        }
        return formatter.formatCellValue(cell);
    }

    // ══ WRITE ════════════════════════════════════════════════════════════════

    /**
     * Builds a file. {@code data} (a list of rows, an object, …) feeds tabular formats;
     * {@code content} (text) feeds document formats. Either falls back to the other.
     */
    public static Written write(String format, Object data, String content, List<Column> columns) throws IOException {
        String fmt = format == null || format.isBlank() ? "CSV" : format.toUpperCase(Locale.ROOT);
        boolean hasContent = content != null && !content.isBlank();
        return switch (fmt) {
            case "CSV" -> {
                Table t = toTable(data != null ? data : content, columns);
                yield new Written(toCsv(t).getBytes(StandardCharsets.UTF_8), "text/csv", "csv", t.rows.size());
            }
            case "EXCEL", "XLSX" -> {
                Table t = toTable(data != null ? data : content, columns);
                yield new Written(toXlsx(t), FileStorageService.guessMimeType("x.xlsx"), "xlsx", t.rows.size());
            }
            case "JSON" -> {
                Object value = data != null ? data : parseJsonOrText(content);
                if (columns != null && !columns.isEmpty() && value != null) value = toTable(value, columns).asObjects();
                yield new Written(JSON.writeValueAsBytes(value), "application/json", "json", value instanceof List<?> l ? l.size() : 1);
            }
            case "MARKDOWN", "MD" -> {
                String body = hasContent ? content : toMarkdown(toTable(data, columns));
                yield new Written(body.getBytes(StandardCharsets.UTF_8), "text/markdown", "md", 0);
            }
            case "HTML" -> {
                String body = hasContent ? content : toHtml(toTable(data, columns));
                yield new Written(body.getBytes(StandardCharsets.UTF_8), "text/html", "html", 0);
            }
            case "PDF" -> {
                String body = hasContent ? content : toPlainTable(toTable(data, columns));
                yield new Written(toPdf(body), "application/pdf", "pdf", 0);
            }
            case "WORD", "DOCX" -> new Written(toDocx(hasContent ? content : null, hasContent ? null : toTable(data, columns)),
                    FileStorageService.guessMimeType("x.docx"), "docx", 0);
            case "TEXT", "TXT" -> {
                String body = hasContent ? content : data == null ? "" : data instanceof String s ? s : JSON.writeValueAsString(data);
                yield new Written(body.getBytes(StandardCharsets.UTF_8), "text/plain", "txt", 0);
            }
            default -> throw new IllegalArgumentException("Unknown output format '" + format
                    + "'. Use CSV, EXCEL, JSON, TEXT, MARKDOWN, HTML, PDF or WORD.");
        };
    }

    /** Header row + data rows ready for any tabular writer. */
    public static final class Table {
        final List<String> headers;
        final List<List<Object>> rows;

        Table(List<String> headers, List<List<Object>> rows) {
            this.headers = headers;
            this.rows = rows;
        }

        public List<String> headers() { return headers; }
        public List<List<Object>> rows() { return rows; }

        List<Map<String, Object>> asObjects() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (List<Object> row : rows) {
                Map<String, Object> obj = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) obj.put(headers.get(i), i < row.size() ? row.get(i) : null);
                out.add(obj);
            }
            return out;
        }
    }

    /** Turns whatever a flow produced into a table. Lists of objects are the usual case. */
    @SuppressWarnings("unchecked")
    public static Table toTable(Object data, List<Column> columns) {
        if (data instanceof String s) {
            String t = s.trim();
            if (t.startsWith("[") || t.startsWith("{")) {
                try {
                    data = JSON.readValue(t, Object.class);
                } catch (IOException ignored) {
                    // plain text: one row per line
                }
            }
            if (data instanceof String text) {
                List<Object> lines = new ArrayList<>(List.of((Object[]) text.split("\\r?\\n")));
                data = lines;
            }
        }
        if (data instanceof Map<?, ?> map) {
            // {"rows": [...]}, {"items": [...]}, a FILE read result, an HTTP body … — use the list inside
            Object inner = null;
            for (String key : List.of("rows", "items", "records", "results", "data")) {
                if (map.get(key) instanceof List<?>) {
                    inner = map.get(key);
                    break;
                }
            }
            if (inner == null) {
                List<Object> lists = map.values().stream().filter(v -> v instanceof List<?>).map(v -> (Object) v).toList();
                if (lists.size() == 1 && map.size() <= 3) inner = lists.get(0);
            }
            data = inner != null ? inner : List.of(map);
        }
        List<Object> items = data instanceof List<?> l ? (List<Object>) l : data == null ? List.of() : List.of(data);
        boolean useColumns = columns != null && !columns.isEmpty();

        if (!items.isEmpty() && items.stream().allMatch(i -> i instanceof List<?>)) {
            // Already rows of cells; the first row is the header unless columns were given.
            List<List<Object>> rows = new ArrayList<>();
            items.forEach(i -> rows.add(new ArrayList<>((List<Object>) i)));
            List<String> headers;
            if (useColumns) {
                headers = columns.stream().map(Column::header).toList();
            } else {
                headers = rows.remove(0).stream().map(String::valueOf).toList();
            }
            return new Table(headers, rows);
        }

        List<String> headers = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        if (useColumns) {
            columns.forEach(c -> {
                headers.add(c.header() != null && !c.header().isBlank() ? c.header() : c.key());
                keys.add(c.key());
            });
        } else {
            Set<String> union = new LinkedHashSet<>();
            for (Object item : items) {
                if (item instanceof Map<?, ?> m) m.keySet().forEach(k -> union.add(String.valueOf(k)));
            }
            if (union.isEmpty()) union.add("value");
            headers.addAll(union);
            keys.addAll(union);
        }

        List<List<Object>> rows = new ArrayList<>();
        for (Object item : items) {
            List<Object> row = new ArrayList<>();
            for (String key : keys) {
                row.add(item instanceof Map<?, ?> m ? valueAt((Map<String, Object>) m, key) : key.equals("value") || keys.size() == 1 ? item : null);
            }
            rows.add(row);
        }
        return new Table(headers, rows);
    }

    /** "customer.name" inside a row; also "items[0].sku". A key containing dots is tried as-is first. */
    @SuppressWarnings("unchecked")
    static Object valueAt(Map<String, Object> row, String path) {
        if (row.containsKey(path)) return row.get(path);
        Object current = row;
        for (String segment : path.split("\\.")) {
            String key = segment;
            Integer index = null;
            int bracket = segment.indexOf('[');
            if (bracket > 0 && segment.endsWith("]")) {
                key = segment.substring(0, bracket);
                try {
                    index = Integer.parseInt(segment.substring(bracket + 1, segment.length() - 1));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            if (!(current instanceof Map<?, ?> m)) return null;
            current = ((Map<String, Object>) m).get(key);
            if (index != null) {
                if (!(current instanceof List<?> list) || index < 0 || index >= list.size()) return null;
                current = list.get(index);
            }
        }
        return current;
    }

    static String cell(Object value) {
        if (value == null) return "";
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            try {
                return new ObjectMapper().writeValueAsString(value);
            } catch (IOException e) {
                return String.valueOf(value);
            }
        }
        if (value instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf(d.longValue());
        return String.valueOf(value);
    }

    public static String toCsv(Table t) {
        StringBuilder sb = new StringBuilder();
        appendCsvRow(sb, new ArrayList<>(t.headers));
        for (List<Object> row : t.rows) appendCsvRow(sb, row);
        return sb.toString();
    }

    private static void appendCsvRow(StringBuilder sb, List<?> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            String v = cell(cells.get(i));
            if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
                sb.append('"').append(v.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(v);
            }
        }
        sb.append("\r\n");
    }

    private static byte[] toXlsx(Table t) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Data");
            CellStyle headerStyle = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            int[] widths = new int[t.headers.size()];
            Row header = sheet.createRow(0);
            for (int c = 0; c < t.headers.size(); c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(t.headers.get(c));
                cell.setCellStyle(headerStyle);
                widths[c] = t.headers.get(c).length();
            }
            for (int r = 0; r < t.rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<Object> values = t.rows.get(r);
                for (int c = 0; c < values.size() && c < 16_000; c++) {
                    Object v = values.get(c);
                    Cell cell = row.createCell(c);
                    if (v instanceof Number num) {
                        cell.setCellValue(num.doubleValue());
                    } else if (v instanceof Boolean b) {
                        cell.setCellValue(b);
                    } else {
                        String s = cell(v);
                        cell.setCellValue(s.length() > 32_000 ? s.substring(0, 32_000) : s);
                    }
                    if (c < widths.length) widths[c] = Math.max(widths[c], Math.min(60, cell(v).length()));
                }
            }
            for (int c = 0; c < widths.length; c++) sheet.setColumnWidth(c, Math.min(255, widths[c] + 2) * 256);
            sheet.createFreezePane(0, 1);
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static String toMarkdown(Table t) {
        if (t.headers.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", t.headers.stream().map(FileFormats::mdCell).toList())).append(" |\n");
        sb.append("|").append(" --- |".repeat(t.headers.size())).append("\n");
        for (List<Object> row : t.rows) {
            sb.append("| ").append(String.join(" | ", row.stream().map(v -> mdCell(cell(v))).toList())).append(" |\n");
        }
        return sb.toString();
    }

    private static String mdCell(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }

    private static String toHtml(Table t) {
        StringBuilder sb = new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><style>"
                + "body{font-family:system-ui,sans-serif;padding:24px}table{border-collapse:collapse}"
                + "th,td{border:1px solid #ccc;padding:6px 10px;text-align:left}th{background:#f3f4f6}"
                + "</style></head><body><table><thead><tr>");
        t.headers.forEach(h -> sb.append("<th>").append(escapeHtml(h)).append("</th>"));
        sb.append("</tr></thead><tbody>");
        for (List<Object> row : t.rows) {
            sb.append("<tr>");
            row.forEach(v -> sb.append("<td>").append(escapeHtml(cell(v))).append("</td>"));
            sb.append("</tr>");
        }
        return sb.append("</tbody></table></body></html>").toString();
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Readable text form of a table, for PDF output. */
    private static String toPlainTable(Table t) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < t.rows.size(); r++) {
            List<Object> row = t.rows.get(r);
            if (t.rows.size() > 1) sb.append("#").append(r + 1).append('\n');
            for (int c = 0; c < t.headers.size(); c++) {
                sb.append(t.headers.get(c)).append(": ").append(c < row.size() ? cell(row.get(c)) : "").append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Simple A4 text document: wraps lines, adds pages as needed. "# " lines become headings. */
    static byte[] toPdf(String text) throws IOException {
        PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        float margin = 50;
        float width = PDRectangle.A4.getWidth() - 2 * margin;

        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfCursor cursor = new PdfCursor(doc, margin);
            for (String raw : (text != null ? text : "").replace("\r\n", "\n").split("\n", -1)) {
                boolean heading = raw.startsWith("#");
                String line = heading ? raw.replaceFirst("^#+\\s*", "") : raw;
                PDType1Font font = heading ? bold : regular;
                float size = heading ? 15 : 11;
                List<String> wrapped = wrap(winAnsi(line, font), font, size, width);
                for (String part : wrapped) cursor.line(part, font, size);
                if (heading) cursor.gap(4);
            }
            cursor.close();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static final class PdfCursor {
        private final PDDocument doc;
        private final float margin;
        private PDPageContentStream stream;
        private float y;

        PdfCursor(PDDocument doc, float margin) {
            this.doc = doc;
            this.margin = margin;
        }

        void line(String text, PDType1Font font, float size) throws IOException {
            float leading = size * 1.45f;
            if (stream == null || y - leading < margin) newPage();
            y -= leading;
            if (!text.isEmpty()) {
                stream.beginText();
                stream.setFont(font, size);
                stream.newLineAtOffset(margin, y);
                stream.showText(text);
                stream.endText();
            }
        }

        void gap(float points) {
            y -= points;
        }

        private void newPage() throws IOException {
            if (stream != null) stream.close();
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            stream = new PDPageContentStream(doc, page);
            y = PDRectangle.A4.getHeight() - margin;
        }

        void close() throws IOException {
            if (stream == null) newPage();
            stream.close();
        }
    }

    /** Standard PDF fonts only cover Latin-1 — replace anything else so the document still renders. */
    private static String winAnsi(String s, PDType1Font font) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            String ch = cp == '\t' ? "    " : new String(Character.toChars(cp));
            try {
                font.encode(ch);
                sb.append(ch);
            } catch (Exception e) {
                sb.append('?');
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    private static List<String> wrap(String line, PDType1Font font, float size, float width) throws IOException {
        List<String> lines = new ArrayList<>();
        if (line.isEmpty()) {
            lines.add("");
            return lines;
        }
        StringBuilder current = new StringBuilder();
        for (String word : line.split(" ", -1)) {
            String candidate = current.length() == 0 ? word : current + " " + word;
            if (font.getStringWidth(candidate) / 1000 * size <= width) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            if (current.length() > 0) lines.add(current.toString());
            current.setLength(0);
            // a single word wider than the page: hard-break it
            String rest = word;
            while (font.getStringWidth(rest) / 1000 * size > width && rest.length() > 1) {
                int cut = rest.length();
                while (cut > 1 && font.getStringWidth(rest.substring(0, cut)) / 1000 * size > width) cut--;
                lines.add(rest.substring(0, cut));
                rest = rest.substring(cut);
            }
            current.append(rest);
        }
        lines.add(current.toString());
        return lines;
    }

    private static byte[] toDocx(String content, Table table) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (content != null) {
                for (String raw : content.replace("\r\n", "\n").split("\n", -1)) {
                    XWPFParagraph p = doc.createParagraph();
                    boolean heading = raw.startsWith("#");
                    var run = p.createRun();
                    run.setText(heading ? raw.replaceFirst("^#+\\s*", "") : raw);
                    if (heading) {
                        run.setBold(true);
                        run.setFontSize(15);
                    }
                }
            } else if (table != null && !table.headers.isEmpty()) {
                XWPFTable t = doc.createTable(table.rows.size() + 1, table.headers.size());
                XWPFTableRow header = t.getRow(0);
                for (int c = 0; c < table.headers.size(); c++) {
                    var run = header.getCell(c).getParagraphs().get(0).createRun();
                    run.setText(table.headers.get(c));
                    run.setBold(true);
                }
                for (int r = 0; r < table.rows.size(); r++) {
                    List<Object> row = table.rows.get(r);
                    for (int c = 0; c < table.headers.size(); c++) {
                        t.getRow(r + 1).getCell(c).setText(c < row.size() ? cell(row.get(c)) : "");
                    }
                }
            }
            doc.write(out);
            return out.toByteArray();
        }
    }

    // ── small helpers ─────────────────────────────────────────────────────────

    private static Object parseJsonOrText(String content) {
        if (content == null) return null;
        String t = content.trim();
        if (t.startsWith("{") || t.startsWith("[")) {
            try {
                return JSON.readValue(t, new TypeReference<Object>() {});
            } catch (IOException ignored) {
                // fall through
            }
        }
        return Map.of("text", content);
    }

    private static String decode(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String unescape(String delimiter) {
        return switch (delimiter) {
            case "\\t", "tab", "TAB" -> "\t";
            default -> delimiter;
        };
    }
}
