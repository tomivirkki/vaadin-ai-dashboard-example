package com.example.reports;

import java.io.ByteArrayInputStream;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.component.HtmlContainer;
import com.vaadin.flow.component.ai.provider.DatabaseProvider;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.dom.Style.Display;
import com.vaadin.flow.server.streams.DownloadHandler;
import com.vaadin.flow.server.streams.DownloadResponse;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * LLM tool for delivering data to the user as a downloadable PDF report
 * without the data ever entering the LLM context. The LLM authors an HTML
 * report <em>template</em> with SQL directives and placeholders in whatever
 * format the user requested, and {@link #createPdfReport(String, String)}
 * executes the queries and fills in the data entirely server-side (see
 * {@link PdfReportGenerator}) before pushing the file to the user's browser.
 * The LLM only ever receives a status message.
 */
public class PdfReportTools {

    private static final Logger LOGGER = LoggerFactory
            .getLogger(PdfReportTools.class);

    private final HtmlContainer downloadAnchorHost;
    private final ReportBranding branding;
    private final PdfReportGenerator generator;

    private Anchor downloadAnchor;

    /**
     * Creates the PDF report tool.
     *
     * @param databaseProvider
     *            database the report data is queried from
     * @param downloadAnchorHost
     *            an attached container the hidden download anchor is added to;
     *            its UI receives the download
     * @param branding
     *            company branding applied to reports
     */
    public PdfReportTools(DatabaseProvider databaseProvider,
            HtmlContainer downloadAnchorHost, ReportBranding branding) {
        this.downloadAnchorHost = downloadAnchorHost;
        this.branding = branding;
        this.generator = new PdfReportGenerator(branding, databaseProvider);
    }

    /**
     * Builds the system prompt fragment describing the PDF report capability:
     * the SQL template directives understood by {@link PdfReportGenerator},
     * the HTML authoring rules, and the branding defaults.
     *
     * @return the system prompt fragment for this tool
     */
    public String getSystemPrompt() {
        return """
                PDF REPORTS:
                The user may ask for data as a downloadable PDF file. Deliver it in the format the user desires by authoring a complete standalone HTML report TEMPLATE (with an inline <style> element) and passing it to the createPdfReport tool. The download then starts automatically in the user's browser — there is no URL to link, so just confirm completion.

                IMPORTANT: the report data stays server-side and never passes through you. The template contains SQL directives that the server executes and fills in while rendering. Never query the data yourself and never write literal data values into the template — always use these directives:
                - Repeating region: the content of an element with a data-query attribute is repeated for every result row, with {{column_label}} placeholders (case-insensitive) replaced by row values — in text and in attributes. Example:
                  <tbody data-query="SELECT &quot;MONTH&quot; AS month, revenue FROM sales WHERE region = 'North' ORDER BY month_order">
                    <tr><td>{{month}}</td><td>{{revenue}}</td><td><img src="qrcode:{{month}}" style="height:12mm"></td></tr>
                  </tbody>
                - Single value: an element with a data-value attribute gets its content replaced with the first column of the first result row, e.g. <span data-value="SELECT SUM(revenue) FROM sales"></span>
                - Directive attributes are regular HTML attributes: quote them with DOUBLE quotes and HTML-escape any double quotes inside the SQL as &quot; (single-quoted SQL string literals need no escaping).
                - Only SELECT statements are allowed. Do all value formatting (rounding, currency, dates, concatenation) in SQL.
                - If the tool returns an error, fix the template and retry.

                HTML authoring rules for PDF:
                - The renderer supports CSS 2.1 print semantics: use tables and block layout, never flexbox or grid.
                - Control the page with @page { size: A4; margin: 18mm } (or the size/orientation the user asks for) and page-break-* properties.
                - Repeating table headers across pages work via <thead>.
                - Company logo: <img src="logo"> (intrinsic size 512x160 px; set an explicit height, e.g. style="height:36px").
                - Barcodes: <img src="barcode:FORMAT:content"> where FORMAT is one of CODE_128, CODE_39, EAN_13, EAN_8, UPC_A, ITF, PDF_417 (rendered 600x150 px). Content may contain {{placeholders}} inside a repeating region.
                - QR codes: <img src="qrcode:content"> (rendered 300x300 px).

                Unless the user asks otherwise, brand the report tastefully with the logo, company name, contact details (e.g. header/footer), the report date, and a QR code linking to the website:
                - Company: %s
                - Address: %s
                - Phone: %s
                - Email: %s
                - Website: %s
                The user's explicit formatting wishes (layout, colors, fonts, grouping, totals, page setup, which branding elements or barcodes to include) always take precedence over these defaults.
                """
                .formatted(branding.companyName(), branding.address(),
                        branding.phone(), branding.email(),
                        branding.website());
    }

    @Tool("""
            Render an HTML report template (containing data-query/data-value \
            SQL directives and {{column}} placeholders instead of data) into \
            a PDF file and send it to the user's browser as a download. The \
            queries are executed and the data is filled in server-side. \
            Returns a status message.""")
    String createPdfReport(
            @P("file name for the download, e.g. sales-report.pdf") String fileName,
            @P("complete standalone HTML report template") String html) {
        byte[] pdf;
        try {
            pdf = generator.generate(html);
        } catch (Exception e) {
            LOGGER.debug("PDF generation failed", e);
            return "Error: PDF generation failed: " + e.getMessage();
        }
        var safeName = sanitizeFileName(fileName);
        var delivered = downloadAnchorHost.getUI().map(ui -> {
            ui.access(() -> startDownload(safeName, pdf));
            return true;
        }).orElse(false);
        return delivered
                ? "PDF \"" + safeName + "\" (" + pdf.length
                        + " bytes) sent to the user's browser as a download"
                : "Error: the widget is no longer attached to a UI";
    }

    private void startDownload(String fileName, byte[] pdf) {
        if (downloadAnchor != null) {
            downloadAnchor.removeFromParent();
        }
        var handler = DownloadHandler.fromInputStream(
                event -> new DownloadResponse(new ByteArrayInputStream(pdf),
                        fileName, "application/pdf", pdf.length),
                fileName);
        downloadAnchor = new Anchor(handler, "");
        downloadAnchor.getElement().setAttribute("download", true);
        downloadAnchor.getStyle().setDisplay(Display.NONE);
        downloadAnchorHost.add(downloadAnchor);
        downloadAnchor.getElement().callJsFunction("click");
    }

    private static String sanitizeFileName(String fileName) {
        var name = fileName == null ? ""
                : fileName.trim().replaceAll("[^\\w.-]+", "-");
        if (name.isBlank() || name.equals(".pdf")) {
            name = "report.pdf";
        }
        return name.toLowerCase(Locale.ROOT).endsWith(".pdf") ? name
                : name + ".pdf";
    }
}
