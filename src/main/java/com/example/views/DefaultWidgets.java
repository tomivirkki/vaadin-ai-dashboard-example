package com.example.views;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.vaadin.flow.component.ai.chart.ChartState;
import com.vaadin.flow.component.ai.common.ChatMessage;
import com.vaadin.flow.component.ai.grid.GridState;
import com.vaadin.flow.component.ai.provider.DatabaseProvider;
import com.vaadin.flow.component.ai.provider.LLMProvider;
import com.vaadin.flow.component.charts.model.AxisType;
import com.vaadin.flow.component.charts.model.ChartType;
import com.vaadin.flow.component.charts.model.Configuration;
import com.vaadin.flow.component.dashboard.DashboardWidget;

/**
 * Static factory for the example widgets shown on a freshly opened dashboard.
 * The widgets are created through the same restore path as AI-generated ones,
 * including a plausible chat history, and their queries target the sales
 * table so the global filter controls affect them.
 */
final class DefaultWidgets {

    private DefaultWidgets() {
    }

    /**
     * Creates the default widgets: a column chart of monthly revenue by
     * region and a grid of all sales records.
     *
     * @param llmProviderFactory
     *            factory for the LLM provider backing each widget's chat
     * @param databaseProvider
     *            database provider the widget queries run through
     * @return the default widgets, ready to be added to the dashboard
     */
    static List<DashboardWidget> create(
            Supplier<LLMProvider> llmProviderFactory,
            DatabaseProvider databaseProvider) {
        return List.of(
                AIDashboardWidget.restore(chartSnapshot(), llmProviderFactory,
                        databaseProvider),
                AIDashboardWidget.restore(gridSnapshot(), llmProviderFactory,
                        databaseProvider));
    }

    private static AIDashboardWidget.WidgetSnapshot chartSnapshot() {
        var chartConfiguration = new Configuration();
        chartConfiguration.getChart().setType(ChartType.COLUMN);
        chartConfiguration.setTitle("Monthly Revenue by Region");
        var xAxis = chartConfiguration.getxAxis();
        xAxis.setType(AxisType.CATEGORY);
        xAxis.setTitle("Month");
        chartConfiguration.getyAxis().setTitle("Revenue");

        return new AIDashboardWidget.WidgetSnapshot(
                AIDashboardWidget.Type.CHART, "Monthly revenue by region", 2, 1,
                null,
                new ChartState(List.of("""
                        SELECT "MONTH" AS category, region AS "_series", SUM(revenue) AS revenue
                        FROM sales
                        GROUP BY "MONTH", month_order, region
                        ORDER BY month_order, region"""),
                        chartConfiguration),
                fakeHistory("Show monthly revenue by region as a column chart",
                        """
                                I've created a column chart of the monthly revenue \
                                with one column series per region. It follows the \
                                global dashboard filters, so you can narrow it down \
                                by date range or region from the toolbar."""));
    }

    private static AIDashboardWidget.WidgetSnapshot gridSnapshot() {
        return new AIDashboardWidget.WidgetSnapshot(
                AIDashboardWidget.Type.GRID, "Sales records", 1, 1,
                new GridState("""
                        SELECT "MONTH" AS "Month", sale_date AS "Sale Date", \
                        region AS "Region", revenue AS "Revenue" \
                        FROM sales ORDER BY month_order, region"""),
                null,
                fakeHistory("List all sales records", """
                        Here are all sales records with their month, sale date, \
                        region, and revenue. The grid follows the global \
                        dashboard filters."""));
    }

    private static List<ChatMessage> fakeHistory(String userPrompt,
            String assistantReply) {
        var now = Instant.now();
        return List.of(
                new ChatMessage(ChatMessage.Role.USER, userPrompt,
                        UUID.randomUUID().toString(), now),
                new ChatMessage(ChatMessage.Role.ASSISTANT, assistantReply,
                        UUID.randomUUID().toString(), now));
    }
}
