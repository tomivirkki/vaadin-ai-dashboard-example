package com.example.views;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.vaadin.flow.component.ai.chart.ChartAIController;
import com.vaadin.flow.component.ai.chart.ChartState;
import com.vaadin.flow.component.ai.common.ChatMessage;
import com.vaadin.flow.component.ai.grid.AIDataRow;
import com.vaadin.flow.component.ai.grid.GridAIController;
import com.vaadin.flow.component.ai.grid.GridState;
import com.vaadin.flow.component.ai.orchestrator.AIOrchestrator;
import com.vaadin.flow.component.ai.provider.DatabaseProvider;
import com.vaadin.flow.component.ai.provider.LLMProvider;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.charts.Chart;
import com.vaadin.flow.component.dashboard.DashboardWidget;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.messages.MessageInput;
import com.vaadin.flow.component.messages.MessageList;
import com.vaadin.flow.component.popover.Popover;
import com.vaadin.flow.component.popover.PopoverPosition;
import com.vaadin.flow.component.popover.PopoverVariant;
import com.vaadin.flow.component.upload.UploadManager;
import com.vaadin.flow.dom.Style.Position;

import dev.langchain4j.agent.tool.Tool;

public class AIDashboardWidget extends DashboardWidget {

    public enum Type {
        GRID, CHART
    }

    private static final String GRID_PROMPT = """
            You control a single data grid.
            Use the provided tools to populate, filter, and explain the grid based on the user's requests.""";

    private static final String CHART_PROMPT = """
            You control a single chart.
            Use the provided tools to query the database and render the chart based on the user's requests.""";

    private static final String UPDATE_TITLE_PROMPT = """
            Based on the content of the widget,
            keep its title up to date by using the updateTitle tool.""";

    private final Type type;
    private final GridAIController gridController;
    private final ChartAIController chartController;
    private final AIOrchestrator orchestrator;
    private final MessageList messageList;
    private final MessageInput messageInput;
    private final UploadManager uploadManager;

    public AIDashboardWidget(Type type, Supplier<LLMProvider> llmProviderFactory,
            DatabaseProvider databaseProvider) {
        this(type, llmProviderFactory, databaseProvider, null, null, null);
    }

    private AIDashboardWidget(Type type,
            Supplier<LLMProvider> llmProviderFactory,
            DatabaseProvider databaseProvider, GridState gridState,
            ChartState chartState, List<ChatMessage> history) {
        this.type = type;
        setTitle(type == Type.GRID ? "Grid widget" : "Chart widget");

        messageList = new MessageList();
        messageInput = new MessageInput();
        uploadManager = new UploadManager(this);

        String systemPrompt;
        if (type == Type.GRID) {
            var grid = new Grid<AIDataRow>();
            grid.setSizeFull();
            gridController = new GridAIController(grid, databaseProvider);
            chartController = null;
            if (gridState != null) {
                gridController.restoreState(gridState);
            }
            setContent(grid);
            systemPrompt = GRID_PROMPT + "\n\n" + UPDATE_TITLE_PROMPT;

            // Temporary workaround for an Aura bug
            // (https://github.com/vaadin/web-components/issues/11563)
            var wrapper = new Div(grid);
            grid.getStyle().setPosition(Position.ABSOLUTE);
            wrapper.getStyle().setPosition(Position.RELATIVE);
            wrapper.setSizeFull();
            setContent(wrapper);
        } else {
            var chart = new Chart();
            chart.setSizeFull();
            chartController = new ChartAIController(chart, databaseProvider);
            gridController = null;
            if (chartState != null) {
                chartController.restoreState(chartState);
            }
            setContent(chart);
            systemPrompt = CHART_PROMPT + "\n\n" + UPDATE_TITLE_PROMPT;
        }

        var chatButton = new Button(VaadinIcon.COMMENT.create());

        var popover = new Popover();
        popover.setTarget(chatButton);
        popover.setPosition(PopoverPosition.END_TOP);
        popover.setModal(true);
        popover.setThemeVariants(PopoverVariant.ARROW);
        var chat = ChatLayouts.build(messageList, messageInput,
                uploadManager);
        chat.setWidth("420px");
        chat.setHeight("500px");

        popover.add(chat);
        popover.addOpenedChangeListener(event -> {
            if (event.isOpened()) {
                messageInput.focus();
            }
        });

        setHeaderContent(new Div(chatButton, popover));

        var provider = llmProviderFactory.get();
        var builder = AIOrchestrator.builder(provider, systemPrompt)
                .withMessageList(messageList).withInput(messageInput)
                .withFileReceiver(uploadManager)
                .withTools(this)
                .withController(type == Type.GRID ? gridController
                        : chartController);
        if (history != null && !history.isEmpty()) {
            builder.withHistory(history, Map.of());
        }
        orchestrator = builder.build();
    }

    public Type getType() {
        return type;
    }

    /**
     * Re-applies the controller's current state, re-running the widget's
     * stored queries through the database provider. Used to reflect global
     * filter changes deterministically, without an LLM round trip.
     */
    public void refresh() {
        if (gridController != null) {
            var state = gridController.getState();
            if (state != null && state.query() != null) {
                gridController.restoreState(state);
            }
        } else {
            var state = chartController.getState();
            if (state != null && state.queries() != null
                    && !state.queries().isEmpty()) {
                chartController.restoreState(state);
            }
        }
    }

    public WidgetSnapshot snapshot() {
        return new WidgetSnapshot(type, getTitle(), getColspan(), getRowspan(),
                gridController != null ? gridController.getState() : null,
                chartController != null ? chartController.getState() : null,
                orchestrator.getHistory());
    }

    public static AIDashboardWidget restore(WidgetSnapshot snapshot,
            Supplier<LLMProvider> llmProviderFactory,
            DatabaseProvider databaseProvider) {
        var widget = new AIDashboardWidget(snapshot.type(), llmProviderFactory,
                databaseProvider, snapshot.gridState(), snapshot.chartState(),
                snapshot.history());
        if (snapshot.title() != null) {
            widget.setTitle(snapshot.title());
        }
        widget.setColspan(snapshot.colspan());
        widget.setRowspan(snapshot.rowspan());
        return widget;
    }

    public record WidgetSnapshot(Type type, String title, int colspan,
            int rowspan, GridState gridState, ChartState chartState,
            List<ChatMessage> history) implements Serializable {
    }

    @Tool("Update the title of the widget to better reflect its content")
    private void updateTitle(String newTitle) {
        getUI().ifPresent(ui -> ui.access(() -> setTitle(newTitle)));
    }
}
