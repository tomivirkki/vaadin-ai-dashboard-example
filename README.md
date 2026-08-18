# Vaadin AI Dashboard Example

A demo showing how to build a lightweight, BI-style dashboard with Vaadin's
AI components. Each widget — a data grid or a chart — embeds its own chat
that drives the underlying view in natural language: ask for a query, change
the visualization, drill into a slice, and the widget updates itself.

<img width="1110" height="911" alt="dashboard-example" src="https://github.com/user-attachments/assets/5af8b86d-8883-4492-b3f0-ba38ae0a76c9" />

## What's in it

- **Composable dashboard** — drag, resize, and arrange grid/chart widgets on
  a [`Dashboard`](https://vaadin.com/docs/latest/components/dashboard).
- **Per-widget AI chat** — each widget has a popover chat backed by an
  `AIOrchestrator` that drives a `GridAIController` or `ChartAIController`.
- **In-memory H2 database** — seeded with a dozen demo tables (sales,
  employees, products, stocks, project tasks, org chart, energy flow,
  traffic heatmap, budget, sales pipeline, KPIs, expenses) covering the
  data shapes for most chart types.
- **PDF reports** — ask any widget's chat for the data as a PDF and
  describe the format you want; the LLM authors only a report *template*
  (HTML with SQL directives and `{{column}}` placeholders), and the server
  executes the queries, fills in the data, and renders the PDF
  (openhtmltopdf) with company branding — logo, contact details — and
  ZXing-generated barcodes/QR codes. The report data never enters the LLM
  context; the download starts in the browser.
- **Save/restore state** — snapshot dashboard layout, widget state, and
  chat history into the Vaadin session.
- **Pluggable LLM** — currently wired to OpenAI via LangChain4J; swap the
  provider in `DashboardView` to use a different model.

## Prerequisites

- Java 21
- A Vaadin Pro/Trial license
- An OpenAI API key in `OPENAI_API_KEY`

## Run

```bash
export OPENAI_API_KEY=sk-...
./mvnw
```

The dashboard is served at <http://localhost:8080/dashboard>. Add a grid or
chart widget from the toolbar, click the chat icon on a widget, and ask
something like _"show monthly revenue by region"_ or _"top 5 products by
units sold"_.

## Project layout

```
src/main/java/com/example/
├── Application.java                 Spring Boot entry point
├── InMemoryDatabaseProvider.java    H2-backed DatabaseProvider
├── DemoDataInitializer.java         Schema + seed data
├── reports/
│   ├── PdfReportTools.java          LLM tool: create PDF from a template
│   ├── PdfReportGenerator.java      SQL-templated HTML → PDF, server-side
│   └── ReportBranding.java          Company name, contacts, logo
└── views/
    ├── DashboardView.java           Top-level @Route("dashboard")
    ├── AIDashboardWidget.java       Grid/chart widget + orchestrator
    └── ChatLayouts.java             Chat layout factory
```

The demo branding (logo in `src/main/resources/reports/logo.png`, contact
details in `ReportBranding.DEMO`) is what the LLM applies to reports by
default; the user's formatting wishes in the chat take precedence. Try
_"give me this data as a PDF invoice-style report with a QR code to the
order page"_.

## Notes

- Vaadin's AI components are experimental — enabled via
  `src/main/resources/vaadin-featureflags.properties`.
- The OpenAI model name is hardcoded in `DashboardView`; change it there.
