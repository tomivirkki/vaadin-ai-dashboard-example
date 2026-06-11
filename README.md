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
- **Global filters** — a date-range + region toolbar that filters every AI
  widget at once, including aggregating and multi-table queries. Filters are
  isolated per user session and applied without an LLM round trip: queries
  run against a `filtered` shadow database of MySQL views whose predicates
  read connection-scoped session variables through small stored functions,
  and each widget simply re-runs its stored SQL unchanged.
- **MySQL database (embedded MariaDB)** — the demo starts a private MariaDB
  server (a drop-in MySQL replacement) as a child process with a temporary
  data directory: no Docker and no database service to manage, only the
  server binaries need to be installed. Seeded with a dozen demo tables
  (sales, employees, products, stocks, project tasks, org chart, energy
  flow, traffic heatmap, budget, sales pipeline, KPIs, expenses) covering
  the data shapes for most chart types.
- **Save/restore state** — snapshot dashboard layout, widget state, and
  chat history into the Vaadin session.
- **Pluggable LLM** — currently wired to OpenAI via LangChain4J; swap the
  provider in `DashboardView` to use a different model.

## Prerequisites

- Java 21
- A Vaadin Pro/Trial license
- An OpenAI API key in `OPENAI_API_KEY`
- MariaDB or MySQL server binaries on the machine, e.g.
  `sudo apt-get install mariadb-server` (the app runs its own private
  instance; no service setup needed)

## Run

```bash
export OPENAI_API_KEY=sk-...
./mvnw
```

The dashboard is served at <http://localhost:8080/dashboard>. It starts with
two example widgets (a revenue chart and a sales grid) that respond to the
global filters. Add more grid or chart widgets from the toolbar, click the
chat icon on a widget, and ask something like _"show monthly revenue by
region"_ or _"top 5 products by units sold"_.

## Project layout

```
src/main/java/com/example/
├── Application.java                 Spring Boot entry point
├── MySqlDatabaseProvider.java       MySQL-dialect DatabaseProvider
├── EmbeddedMariaDb.java             Private MariaDB server lifecycle
├── DemoDataInitializer.java         Schema + seed data
└── views/
    ├── DashboardView.java           Top-level @Route("dashboard")
    ├── AIDashboardWidget.java       Grid/chart widget + orchestrator
    └── ChatLayouts.java             Chat layout factory
```

## Notes

- Vaadin's AI components are experimental — enabled via
  `src/main/resources/vaadin-featureflags.properties`.
- The OpenAI model name is hardcoded in `DashboardView`; change it there.
