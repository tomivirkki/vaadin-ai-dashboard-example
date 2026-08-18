package com.example.reports;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.vaadin.flow.component.ai.provider.DatabaseProvider;

/**
 * Renders an HTML report template into a PDF. The template contains SQL
 * directives instead of data, so the data is queried and filled in entirely
 * server-side and never has to pass through the template author (e.g. an
 * LLM):
 * <ul>
 * <li>{@code <tbody data-query='SELECT ...'>} — the element's content is
 * treated as a row template and repeated for every result row, with
 * {@code {{column_label}}} placeholders (case-insensitive, also inside
 * attributes) replaced by row values</li>
 * <li>{@code <span data-value='SELECT ...'>} — the element's content is
 * replaced with the first column of the first result row</li>
 * </ul>
 * Only {@code SELECT} statements are allowed in directives.
 * <p>
 * The HTML may also reference special image sources that are resolved into
 * embedded images:
 * <ul>
 * <li>{@code <img src="logo">} — the company logo from
 * {@link ReportBranding#logoResource()}</li>
 * <li>{@code <img src="barcode:FORMAT:content">} — a 1D barcode, e.g.
 * {@code barcode:CODE_128:INV-2026-001}</li>
 * <li>{@code <img src="qrcode:content">} — a QR code</li>
 * </ul>
 */
public class PdfReportGenerator {

    private static final String QUERY_ATTR = "data-query";
    private static final String VALUE_ATTR = "data-value";
    private static final Pattern PLACEHOLDER = Pattern
            .compile("\\{\\{\\s*([^{}]+?)\\s*}}");

    private static final String LOGO_SRC = "logo";
    private static final String BARCODE_PREFIX = "barcode:";
    private static final String QRCODE_PREFIX = "qrcode:";
    private static final String PNG_DATA_URI_PREFIX = "data:image/png;base64,";

    private static final Set<BarcodeFormat> SQUARE_FORMATS = Set
            .of(BarcodeFormat.QR_CODE, BarcodeFormat.AZTEC,
                    BarcodeFormat.DATA_MATRIX);

    private final ReportBranding branding;
    private final DatabaseProvider databaseProvider;
    private byte[] logoBytes;

    /**
     * Creates a generator.
     *
     * @param branding
     *            branding providing the logo image resource
     * @param databaseProvider
     *            database the report data is queried from
     */
    public PdfReportGenerator(ReportBranding branding,
            DatabaseProvider databaseProvider) {
        this.branding = branding;
        this.databaseProvider = databaseProvider;
    }

    /**
     * Expands the SQL directives in the given HTML report template and
     * renders the result into a PDF.
     *
     * @param html
     *            a complete HTML document with {@code data-query} /
     *            {@code data-value} directives; non-well-formed markup is
     *            cleaned up automatically
     * @return the PDF file contents
     * @throws IOException
     *             if PDF rendering fails
     * @throws IllegalArgumentException
     *             if a directive query fails or is not a SELECT, a
     *             placeholder references an unknown column, or the HTML
     *             references an invalid barcode or QR code
     */
    public byte[] generate(String html) throws IOException {
        var document = Jsoup.parse(html);
        document.select("[" + QUERY_ATTR + "]")
                .forEach(this::expandQueryDirective);
        document.select("[" + VALUE_ATTR + "]")
                .forEach(this::expandValueDirective);
        document.select("img").forEach(this::resolveImageSource);

        var out = new ByteArrayOutputStream();
        var builder = new PdfRendererBuilder();
        builder.useFastMode();
        builder.withW3cDocument(new W3CDom().fromJsoup(document), null);
        builder.toStream(out);
        builder.run();
        return out.toByteArray();
    }

    private void expandQueryDirective(Element element) {
        var rows = executeSelect(element.attr(QUERY_ATTR));
        element.removeAttr(QUERY_ATTR);
        var rowTemplate = List.copyOf(element.childNodes());
        element.empty();
        for (var row : rows) {
            var values = caseInsensitive(row);
            for (var templateNode : rowTemplate) {
                var clone = templateNode.clone();
                substitutePlaceholders(clone, values);
                element.appendChild(clone);
            }
        }
    }

    private void expandValueDirective(Element element) {
        var sql = element.attr(VALUE_ATTR);
        var rows = executeSelect(sql);
        element.removeAttr(VALUE_ATTR);
        if (rows.isEmpty() || rows.get(0).isEmpty()) {
            throw new IllegalArgumentException(
                    "data-value query returned no value: " + sql);
        }
        var value = rows.get(0).values().iterator().next();
        element.text(value == null ? "" : value.toString());
    }

    private List<Map<String, Object>> executeSelect(String sql) {
        if (!sql.trim().toLowerCase(Locale.ROOT)
                .matches("(?s)^(select|with)\\b.*")) {
            throw new IllegalArgumentException(
                    "Only SELECT queries are allowed in directives: " + sql);
        }
        return databaseProvider.executeQuery(sql);
    }

    private static Map<String, Object> caseInsensitive(
            Map<String, Object> row) {
        var values = new TreeMap<String, Object>(
                String.CASE_INSENSITIVE_ORDER);
        values.putAll(row);
        return values;
    }

    private static void substitutePlaceholders(Node node,
            Map<String, Object> values) {
        if (node instanceof TextNode textNode) {
            textNode.text(substitute(textNode.getWholeText(), values));
        } else if (node instanceof Element element) {
            element.attributes().forEach(attribute -> attribute
                    .setValue(substitute(attribute.getValue(), values)));
            element.childNodes()
                    .forEach(child -> substitutePlaceholders(child, values));
        }
    }

    private static String substitute(String text,
            Map<String, Object> values) {
        return PLACEHOLDER.matcher(text).replaceAll(match -> {
            var column = match.group(1);
            if (!values.containsKey(column)) {
                throw new IllegalArgumentException("Unknown column \"" + column
                        + "\" in placeholder, available columns: "
                        + values.keySet());
            }
            var value = values.get(column);
            return Matcher
                    .quoteReplacement(value == null ? "" : value.toString());
        });
    }

    private void resolveImageSource(Element img) {
        var src = img.attr("src").trim();
        if (src.equals(LOGO_SRC)) {
            img.attr("src", PNG_DATA_URI_PREFIX
                    + Base64.getEncoder().encodeToString(getLogoBytes()));
        } else if (src.startsWith(BARCODE_PREFIX)) {
            var parts = src.substring(BARCODE_PREFIX.length()).split(":", 2);
            if (parts.length != 2 || parts[1].isBlank()) {
                throw new IllegalArgumentException(
                        "Invalid barcode source \"" + src
                                + "\", expected barcode:FORMAT:content");
            }
            img.attr("src", barcodeDataUri(parseFormat(parts[0]), parts[1]));
        } else if (src.startsWith(QRCODE_PREFIX)) {
            img.attr("src", barcodeDataUri(BarcodeFormat.QR_CODE,
                    src.substring(QRCODE_PREFIX.length())));
        }
    }

    private static BarcodeFormat parseFormat(String format) {
        try {
            return BarcodeFormat
                    .valueOf(format.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unsupported barcode format \"" + format + "\"", e);
        }
    }

    private static String barcodeDataUri(BarcodeFormat format,
            String content) {
        var square = SQUARE_FORMATS.contains(format);
        int width = square ? 300 : 600;
        int height = square ? 300 : 150;
        try {
            var matrix = new MultiFormatWriter().encode(content, format, width,
                    height);
            var out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return PNG_DATA_URI_PREFIX
                    + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (WriterException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Cannot encode \"" + content
                    + "\" as " + format + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private byte[] getLogoBytes() {
        if (logoBytes == null) {
            try (var in = getClass()
                    .getResourceAsStream(branding.logoResource())) {
                if (in == null) {
                    throw new IllegalStateException("Logo resource not found: "
                            + branding.logoResource());
                }
                logoBytes = in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return logoBytes;
    }
}
