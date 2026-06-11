package com.example.views;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

import com.example.InMemoryDatabaseProvider;
import com.vaadin.flow.component.ai.provider.LLMProvider;
import com.vaadin.flow.component.ai.provider.LangChain4JLLMProvider;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.contextmenu.HasMenuItems;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.dashboard.Dashboard;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.menubar.MenuBarVariant;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinSession;

import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

@Route("dashboard")
public class DashboardView extends VerticalLayout {

    private static final String STATE_ATTRIBUTE = DashboardView.class.getName()
            + ".state";

    private final Dashboard dashboard;
    private final InMemoryDatabaseProvider databaseProvider;
    private final Supplier<LLMProvider> llmProviderFactory;

    private final DatePicker fromDateFilter = new DatePicker("From date");
    private final DatePicker toDateFilter = new DatePicker("To date");
    private final CheckboxGroup<String> regionFilter = new CheckboxGroup<>(
            "Regions");

    public DashboardView() {
        setSizeFull();
        setPadding(false);
        setSpacing(false);

        // One provider instance per view (i.e. per browser tab), so the
        // global filter values are isolated between user sessions
        databaseProvider = new InMemoryDatabaseProvider();

        var chatModel = OpenAiStreamingChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-5.4-mini").build();
        llmProviderFactory = () -> new LangChain4JLLMProvider(chatModel);

        dashboard = new Dashboard();
        dashboard.setSizeFull();
        dashboard.setEditable(true);
        dashboard.setMaximumColumnCount(3);
        dashboard.setMinimumRowHeight("300px");

        var addMenu = new MenuBar();
        addMenu.addThemeVariants(MenuBarVariant.LUMO_PRIMARY);
        var addItem = addMenu.addItem("Add widget");
        var subMenu = addItem.getSubMenu();
        addIconItem(subMenu, VaadinIcon.GRID, "Grid",
                e -> addWidget(AIDashboardWidget.Type.GRID));
        addIconItem(subMenu, VaadinIcon.CHART, "Chart",
                e -> addWidget(AIDashboardWidget.Type.CHART));

        var saveButton = new Button("Save state", VaadinIcon.DOWNLOAD.create(),
                e -> saveState());
        var restoreButton = new Button("Restore state",
                VaadinIcon.UPLOAD.create(), e -> restoreState());

        var toolbar = new HorizontalLayout(addMenu, saveButton, restoreButton);
        toolbar.setPadding(true);
        toolbar.setWidthFull();

        add(toolbar, createFilterBar(), dashboard);
        expand(dashboard);

        // Example widgets so the dashboard is not empty on first visit
        dashboard.add(DefaultWidgets.create(llmProviderFactory,
                databaseProvider));
    }

    /**
     * Builds the global filter toolbar. Every change pushes the new filter
     * values into the database provider and re-applies the current state of
     * each AI widget, re-running its stored queries against the filtered data
     * — no LLM round trip involved.
     */
    private HorizontalLayout createFilterBar() {
        fromDateFilter.setClearButtonVisible(true);
        // The demo data lives in 2025, so open the calendars there
        fromDateFilter.setInitialPosition(LocalDate.of(2025, 1, 1));
        toDateFilter.setClearButtonVisible(true);
        toDateFilter.setInitialPosition(LocalDate.of(2025, 1, 1));
        regionFilter.setItems(databaseProvider
                .executeQuery("SELECT DISTINCT region FROM sales ORDER BY region")
                .stream().map(row -> (String) row.get("REGION")).toList());

        fromDateFilter.addValueChangeListener(e -> applyFilters());
        toDateFilter.addValueChangeListener(e -> applyFilters());
        regionFilter.addValueChangeListener(e -> applyFilters());

        var filterBar = new HorizontalLayout(fromDateFilter, toDateFilter,
                regionFilter);
        filterBar.setPadding(true);
        filterBar.setWidthFull();
        filterBar.setAlignItems(FlexComponent.Alignment.BASELINE);
        return filterBar;
    }

    private void applyFilters() {
        databaseProvider.setFilters(fromDateFilter.getValue(),
                toDateFilter.getValue(), regionFilter.getValue());
        aiWidgets().forEach(AIDashboardWidget::refresh);
    }

    private void addWidget(AIDashboardWidget.Type type) {
        var widget = new AIDashboardWidget(type, llmProviderFactory,
                databaseProvider);
        dashboard.add(List.of(widget));
    }

    private List<AIDashboardWidget> aiWidgets() {
        return dashboard.getWidgets().stream()
                .filter(AIDashboardWidget.class::isInstance)
                .map(AIDashboardWidget.class::cast).toList();
    }

    private static MenuItem addIconItem(HasMenuItems menu, VaadinIcon iconName,
            String label,
            ComponentEventListener<ClickEvent<MenuItem>> listener) {
        var icon = new Icon(iconName);
        var item = menu.addItem(icon, listener);
        item.add(new Text(label));
        return item;
    }

    private void saveState() {
        var snapshots = aiWidgets().stream().map(AIDashboardWidget::snapshot)
                .toList();
        if (snapshots.isEmpty()) {
            Notification.show("No widgets to save");
            return;
        }
        VaadinSession.getCurrent().setAttribute(STATE_ATTRIBUTE, snapshots);
        Notification.show("Saved " + snapshots.size() + " widget(s)");
    }

    @SuppressWarnings("unchecked")
    private void restoreState() {
        var snapshots = (List<AIDashboardWidget.WidgetSnapshot>) VaadinSession
                .getCurrent().getAttribute(STATE_ATTRIBUTE);
        if (snapshots == null || snapshots.isEmpty()) {
            Notification.show("No saved state");
            return;
        }
        dashboard.removeAll();
        for (var snapshot : snapshots) {
            var widget = AIDashboardWidget.restore(snapshot,
                    llmProviderFactory, databaseProvider);
            dashboard.add(List.of(widget));
        }
        Notification.show("Restored " + snapshots.size() + " widget(s)");
    }
}
