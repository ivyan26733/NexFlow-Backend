package com.nexflow.nexflow_backend.executor.file;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileFormatsTest {

    @Test
    void csvIsReadIntoRowsKeyedByHeader() throws Exception {
        String csv = "name,amount,code\r\n\"Smith, Jo\",42,007\nAna,3.5,\"say \"\"hi\"\"\"\n";
        Map<String, Object> out = FileFormats.read(csv.getBytes(StandardCharsets.UTF_8), "orders.csv", "text/csv", "AUTO", null, null);

        assertThat(out.get("format")).isEqualTo("CSV");
        assertThat(out.get("columns")).isEqualTo(List.of("name", "amount", "code"));
        assertThat(out.get("rowCount")).isEqualTo(2);
        List<Map<String, Object>> rows = rows(out);
        assertThat(rows.get(0)).containsEntry("name", "Smith, Jo").containsEntry("amount", 42L).containsEntry("code", "007");
        assertThat(rows.get(1)).containsEntry("amount", 3.5).containsEntry("code", "say \"hi\"");
    }

    @Test
    void semicolonDelimiterIsDetected() throws Exception {
        Map<String, Object> out = FileFormats.read("a;b\n1;2\n".getBytes(StandardCharsets.UTF_8), "x.csv", null, "AUTO", null, null);
        assertThat(rows(out).get(0)).containsEntry("a", 1L).containsEntry("b", 2L);
    }

    @Test
    void writtenExcelReadsBackWithChosenColumns() throws Exception {
        List<Map<String, Object>> data = List.of(
                Map.of("customer", Map.of("name", "Acme"), "amount", 120, "secret", "x"),
                Map.of("customer", Map.of("name", "Globex"), "amount", 80.5, "secret", "y"));
        List<FileFormats.Column> columns = List.of(new FileFormats.Column("Customer", "customer.name"), new FileFormats.Column("Total", "amount"));

        FileFormats.Written xlsx = FileFormats.write("EXCEL", data, null, columns);
        assertThat(xlsx.extension()).isEqualTo("xlsx");
        assertThat(xlsx.rowCount()).isEqualTo(2);

        Map<String, Object> back = FileFormats.read(xlsx.bytes(), "r.xlsx", null, "AUTO", null, null);
        assertThat(back.get("columns")).isEqualTo(List.of("Customer", "Total"));
        assertThat(rows(back).get(0)).containsEntry("Customer", "Acme").containsEntry("Total", 120L);
        assertThat(rows(back).get(1)).containsEntry("Total", 80.5);
    }

    @Test
    void csvWriteQuotesAndUsesUnionOfKeys() throws Exception {
        FileFormats.Written csv = FileFormats.write("CSV", List.of(Map.of("a", "x,y"), Map.of("b", "line\nbreak")), null, List.of());
        String text = new String(csv.bytes(), StandardCharsets.UTF_8);
        assertThat(text).startsWith("a,b\r\n").contains("\"x,y\",").contains("\"line\nbreak\"");
    }

    @Test
    void readResultObjectIsUnwrappedToItsRows() {
        Map<String, Object> readResult = Map.of("format", "CSV", "columns", List.of("n"), "rows", List.of(Map.of("n", 1)), "rowCount", 1);
        FileFormats.Table t = FileFormats.toTable(readResult, null);
        assertThat(t.headers()).containsExactly("n");
        assertThat(t.rows()).hasSize(1);
    }

    @Test
    void pdfIsWrittenAndItsTextReadBack() throws Exception {
        FileFormats.Written pdf = FileFormats.write("PDF", null, "# Monthly report\nTotal: 42 — done ✓", null);
        try (PDDocument doc = Loader.loadPDF(pdf.bytes())) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Monthly report").contains("Total: 42");
        }
        Map<String, Object> back = FileFormats.read(pdf.bytes(), "r.pdf", "application/pdf", "AUTO", null, null);
        assertThat(back.get("pages")).isEqualTo(1);
        assertThat((String) back.get("text")).contains("Monthly report");
    }

    @Test
    void wordAndJsonRoundTrip() throws Exception {
        FileFormats.Written docx = FileFormats.write("WORD", null, "# Title\nBody text", null);
        assertThat((String) FileFormats.read(docx.bytes(), "a.docx", null, "AUTO", null, null).get("text")).contains("Title").contains("Body text");

        FileFormats.Written json = FileFormats.write("JSON", List.of(Map.of("a", 1)), null, null);
        assertThat(FileFormats.read(json.bytes(), "a.json", null, "AUTO", null, null).get("data")).isEqualTo(List.of(Map.of("a", 1)));
    }

    @Test
    void unknownFormatIsRejected() {
        assertThatThrownBy(() -> FileFormats.write("ZIP", List.of(), null, null)).hasMessageContaining("Unknown output format");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("rows");
    }
}
