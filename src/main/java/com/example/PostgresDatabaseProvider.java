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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import com.vaadin.flow.component.ai.provider.DatabaseProvider;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Embedded PostgreSQL implementation of DatabaseProvider for testing and demo
 * purposes. A real PostgreSQL server is started once per JVM as a child
 * process (no Docker or local installation needed) and seeded with demo data.
 * <p>
 * Supports global dashboard filters: each instance holds its own filter values
 * (set via {@link #setFilters(LocalDate, LocalDate, Set)}), so instantiating
 * one provider per UI/view keeps filters isolated per user session even though
 * the underlying database is shared. The filters are applied through
 * connection-scoped PostgreSQL settings: before executing a query, the
 * connection's {@code search_path} is pointed at the {@code filtered} schema
 * (falling back to {@code public}) and the filter values are bound to custom
 * session settings ({@code app.*} GUCs) read by the views in that schema via
 * {@code current_setting()}. The query text itself is never rewritten, so
 * aggregating and multi-table SQL stay correct.
 *
 * @author Vaadin Ltd
 */
public class PostgresDatabaseProvider implements DatabaseProvider {

    private static final String UNSET = "";

    private static DataSource dataSource;

    // Written from the UI thread, read from LLM tool-call threads
    private volatile LocalDate fromDate;
    private volatile LocalDate toDate;
    private volatile Set<String> regions;

    public PostgresDatabaseProvider() {
        DemoDataInitializer.initialize(dataSource());
    }

    private static synchronized DataSource dataSource() {
        if (dataSource == null) {
            try {
                // Started once per JVM; stopped automatically on JVM exit
                dataSource = EmbeddedPostgres.start().getPostgresDatabase();
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Failed to start embedded PostgreSQL", e);
            }
        }
        return dataSource;
    }

    /**
     * Stores the global filter values used by all subsequent queries made
     * through this provider instance. Does not touch the database; the values
     * are bound to connection-scoped session settings on each query.
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

        // Describe only the base tables in the public schema, so the filtered
        // shadow schema is excluded and each table is described once
        var columnsQuery = """
                SELECT c.table_name, c.column_name, c.data_type,
                       c.character_maximum_length, c.numeric_precision,
                       c.numeric_scale, c.is_nullable
                FROM information_schema.columns c
                JOIN information_schema.tables t
                  ON t.table_schema = c.table_schema
                 AND t.table_name = c.table_name
                WHERE c.table_schema = 'public'
                  AND t.table_type = 'BASE TABLE'
                ORDER BY c.table_name, c.ordinal_position
                """;

        try (var conn = dataSource().getConnection();
            var stmt = conn.prepareStatement(columnsQuery);
            var rs = stmt.executeQuery()) {
            String currentTable = null;
            while (rs.next()) {
                var tableName = rs.getString("table_name");
                if (!tableName.equals(currentTable)) {
                    if (currentTable != null) {
                        schema.append(");\n\n");
                    }
                    schema.append("CREATE TABLE ").append(quoted(tableName))
                            .append(" (");
                    currentTable = tableName;
                } else {
                    schema.append(",");
                }
                schema.append("\n    ")
                        .append(quoted(rs.getString("column_name")))
                        .append(" ").append(formatType(rs));
                if ("NO".equals(rs.getString("is_nullable"))) {
                    schema.append(" NOT NULL");
                }
            }
            if (currentTable != null) {
                schema.append(");\n\n");
            }

            schema.append("""
                NOTES:
                - This is a PostgreSQL database
                - The "MONTH" column of the sales table is an upper-case identifier and must always be double-quoted; unquoted identifiers fold to lower case
                - Do NOT use reserved words like VALUE, KEY, ORDER, etc. as column aliases. Use descriptive names instead (e.g. total_revenue, sale_count)
                - All tables support standard SQL SELECT queries
                - sales table: use month_order column for chronological sorting (ORDER BY month_order)
                - website_traffic table: both columns are 0-based indices. day_of_week: 0=Monday, 1=Tuesday, 2=Wednesday, 3=Thursday, 4=Friday. hour_of_day: 0=9am, 1=10am, ..., 7=4pm. Use xAxis/yAxis categories in configuration to set the display labels.
                - GLOBAL DASHBOARD FILTERS: the dashboard has global filters (date range, regions) that are applied automatically by the database layer. The tables already contain only the rows matching the user's current filters. Do NOT add your own WHERE conditions for date ranges or regions to compensate or replicate these global filters; write queries as if the tables held exactly the data the user wants to see. Only add such conditions when the user explicitly asks for additional filtering in their request.
                - Always reference tables by their plain unqualified names (e.g. sales, NOT public.sales), otherwise the global dashboard filters will not apply
                """);

        } catch (SQLException e) {
            return "Error retrieving database schema: " + e.getMessage();
        }

        return schema.toString();
    }

    private static String quoted(String identifier) {
        // Quote identifiers that don't survive PostgreSQL's lower-case folding
        return identifier.matches("[a-z_][a-z0-9_]*") ? identifier
                : "\"" + identifier + "\"";
    }

    private static String formatType(java.sql.ResultSet rs)
            throws SQLException {
        var dataType = rs.getString("data_type");
        var charLength = rs.getObject("character_maximum_length",
                Integer.class);
        if (charLength != null) {
            return dataType + "(" + charLength + ")";
        }
        var precision = rs.getObject("numeric_precision", Integer.class);
        var scale = rs.getObject("numeric_scale", Integer.class);
        if ("numeric".equals(dataType) && precision != null) {
            return dataType + "(" + precision + ", " + scale + ")";
        }
        return dataType;
    }

    @Override
    public List<Map<String, Object>> executeQuery(String sql) {
        try (var conn = dataSource().getConnection()) {
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
     * table names resolve to the views in the filtered schema when one exists
     * (and to the unfiltered public base table otherwise), and the views read
     * this instance's filter values from custom session settings. An empty
     * string means "no filter" — the views translate it to NULL via NULLIF.
     * All of these settings are scoped to the given connection, so concurrent
     * sessions with different filter values never interfere.
     */
    private void applyFiltersToConnection(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement()) {
            stmt.execute("SET search_path TO filtered, public");
        }
        try (var stmt = conn.prepareStatement("""
                SELECT set_config('app.from_date', ?, false),
                       set_config('app.to_date', ?, false),
                       set_config('app.regions', ?, false)
                """)) {
            stmt.setString(1, fromDate == null ? UNSET : fromDate.toString());
            stmt.setString(2, toDate == null ? UNSET : toDate.toString());
            stmt.setString(3, regions == null ? UNSET : toArrayLiteral(regions));
            stmt.executeQuery().close();
        }
    }

    private static String toArrayLiteral(Set<String> values) {
        // PostgreSQL text[] literal, e.g. {"North","South"}
        return values.stream()
                .map(value -> "\"" + value.replace("\\", "\\\\")
                        .replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }
}
