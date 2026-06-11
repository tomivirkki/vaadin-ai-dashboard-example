/*
 * Copyright 2000-2026 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.vaadin.flow.component.ai.provider.DatabaseProvider;

/**
 * In-memory H2 database implementation of DatabaseProvider for testing and demo
 * purposes.
 * <p>
 * Supports global dashboard filters: each instance holds its own filter values
 * (set via {@link #setFilters(LocalDate, LocalDate, Set)}), so instantiating
 * one provider per UI/view keeps filters isolated per user session even though
 * the underlying in-memory database is shared. The filters are applied through
 * connection-scoped H2 settings: before executing a query, the connection is
 * switched to the {@code FILTERED} schema (falling back to {@code PUBLIC} via
 * the schema search path) and the filter values are bound to H2 user
 * variables read by the views in that schema. The query text itself is never
 * rewritten, so aggregating and multi-table SQL stay correct.
 *
 * @author Vaadin Ltd
 */
public class InMemoryDatabaseProvider implements DatabaseProvider {

    private static final String DB_URL = "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1";
    private static final String DB_USER = "sa";
    private static final String DB_PASSWORD = "";
    private static final String NULL_LITERAL = "NULL";

    // Written from the UI thread, read from LLM tool-call threads
    private volatile LocalDate fromDate;
    private volatile LocalDate toDate;
    private volatile Set<String> regions;

    public InMemoryDatabaseProvider() {
        DemoDataInitializer.initialize(DB_URL, DB_USER, DB_PASSWORD);
    }

    /**
     * Stores the global filter values used by all subsequent queries made
     * through this provider instance. Does not touch the database; the values
     * are bound to connection-scoped H2 user variables on each query.
     *
     * @param fromDate
     *            inclusive start date, or {@code null} for no lower bound
     * @param toDate
     *            inclusive end date, or {@code null} for no upper bound
     * @param regions
     *            regions to include; {@code null} or empty means no region
     *            filter
     */
    public void setFilters(LocalDate fromDate, LocalDate toDate,
            Set<String> regions) {
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.regions = regions == null || regions.isEmpty() ? null
                : Set.copyOf(regions);
    }

    @Override
    public String getSchema() {
        var schema = new StringBuilder("DATABASE SCHEMA:\n\n");

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            try (Statement stmt = conn.createStatement();
                // H2-specific SQL command that exports the database schema as a series of DDL statements (requires admin)
                var rs = stmt.executeQuery("SCRIPT NODATA NOSETTINGS")) {
                while (rs.next()) {
                    var line = rs.getString(1);
                    // Skip the FILTERED shadow schema so each table is described once
                    if (line.contains("\"FILTERED\"")) {
                        continue;
                    }
                    if (line.startsWith("CREATE TABLE") || line.startsWith("CREATE MEMORY TABLE")) {
                        // Drop the schema qualifier so the LLM uses unqualified
                        // names, which resolve through the filtered views
                        schema.append(line.replace("\"PUBLIC\".", "")).append("\n\n");
                    }
                }
            }

            schema.append("""
                NOTES:
                - This is an H2 database. Reserved words like MONTH, VALUE, etc. must be quoted with double quotes when used as identifiers
                - Use "MONTH" with quotes when querying the sales table (reserved word)
                - value is a reserved alias
                - Do NOT use reserved words like VALUE, KEY, ORDER, etc. as column aliases. Use descriptive names instead (e.g. total_revenue, sale_count)
                - All tables support standard SQL SELECT queries
                - SALES table: use month_order column for chronological sorting (ORDER BY month_order)
                - WEBSITE_TRAFFIC table: both columns are 0-based indices. day_of_week: 0=Monday, 1=Tuesday, 2=Wednesday, 3=Thursday, 4=Friday. hour_of_day: 0=9am, 1=10am, ..., 7=4pm. Use xAxis/yAxis categories in configuration to set the display labels.
                - GLOBAL DASHBOARD FILTERS: the dashboard has global filters (date range, regions) that are applied automatically by the database layer. The tables already contain only the rows matching the user's current filters. Do NOT add your own WHERE conditions for date ranges or regions to compensate or replicate these global filters; write queries as if the tables held exactly the data the user wants to see. Only add such conditions when the user explicitly asks for additional filtering in their request.
                - Always reference tables by their plain unqualified names (e.g. sales, NOT public.sales), otherwise the global dashboard filters will not apply
                """);

        } catch (SQLException e) {
            return "Error retrieving database schema: " + e.getMessage();
        }

        return schema.toString();
    }

    @Override
    public List<Map<String, Object>> executeQuery(String sql) {
        try (var conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            applyFiltersToConnection(conn);
            try (var stmt = conn.prepareStatement(sql);
                var rs = stmt.executeQuery()) {
                // Convert ResultSet to List of Maps (one map per row, column names as keys)
                var meta = rs.getMetaData();
                var rows = new ArrayList<Map<String, Object>>();
                while (rs.next()) {
                    var row = new LinkedHashMap<String, Object>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalArgumentException("Query failed: " + e.getMessage(), e);
        }
    }

    /**
     * Applies the connection-scoped settings that make the global filters
     * effective for any SQL subsequently executed on {@code conn}: unqualified
     * table names resolve to the views in the FILTERED schema when one exists
     * (and to the unfiltered PUBLIC base table otherwise), and the views read
     * this instance's filter values from H2 user variables. All of these
     * settings are scoped to the given connection, so concurrent sessions
     * with different filter values never interfere.
     */
    private void applyFiltersToConnection(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement()) {
            stmt.execute("SET SCHEMA filtered");
            stmt.execute("SET SCHEMA_SEARCH_PATH filtered, public");
            stmt.execute("SET @from_date = " + toDateLiteral(fromDate));
            stmt.execute("SET @to_date = " + toDateLiteral(toDate));
            stmt.execute("SET @regions = " + toArrayLiteral(regions));
        }
    }

    private static String toDateLiteral(LocalDate date) {
        // LocalDate.toString() is strict ISO-8601, safe to inline
        return date == null ? NULL_LITERAL : "DATE '" + date + "'";
    }

    private static String toArrayLiteral(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return NULL_LITERAL;
        }
        return values.stream().map(value -> "'" + value.replace("'", "''") + "'")
                .collect(Collectors.joining(", ", "ARRAY[", "]"));
    }
}
