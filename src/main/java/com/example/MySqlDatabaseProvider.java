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
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import com.mysql.cj.jdbc.MysqlDataSource;
import com.vaadin.flow.component.ai.provider.DatabaseProvider;

/**
 * MySQL implementation of DatabaseProvider for testing and demo purposes. The
 * demo database is a private MariaDB server (a drop-in MySQL replacement)
 * started once per JVM by {@link EmbeddedMariaDb} and seeded with demo data;
 * the SQL used here is plain MySQL dialect.
 * <p>
 * Supports global dashboard filters: each instance holds its own filter values
 * (set via {@link #setFilters(LocalDate, LocalDate, Set)}), so instantiating
 * one provider per UI/view keeps filters isolated per user session even though
 * the underlying database is shared. The filters are applied through
 * connection-scoped settings: before executing a query, the connection's
 * default database is switched to the {@code filtered} shadow database and the
 * filter values are bound to session user variables ({@code @from_date} etc.)
 * read by the views there through small stored functions. MySQL has no schema
 * search path, so the shadow database also contains unfiltered passthrough
 * views for the tables the filters don't apply to. The query text itself is
 * never rewritten, so aggregating and multi-table SQL stay correct.
 *
 * @author Vaadin Ltd
 */
public class MySqlDatabaseProvider implements DatabaseProvider {

    private static DataSource dataSource;

    // Written from the UI thread, read from LLM tool-call threads
    private volatile LocalDate fromDate;
    private volatile LocalDate toDate;
    private volatile Set<String> regions;

    public MySqlDatabaseProvider() {
        DemoDataInitializer.initialize(dataSource());
    }

    private static synchronized DataSource dataSource() {
        if (dataSource == null) {
            var mysqlDataSource = new MysqlDataSource();
            mysqlDataSource.setUrl(EmbeddedMariaDb.start());
            dataSource = mysqlDataSource;
        }
        return dataSource;
    }

    /**
     * Stores the global filter values used by all subsequent queries made
     * through this provider instance. Does not touch the database; the values
     * are bound to connection-scoped session variables on each query.
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

        // Describe only the base tables in the demo database, so the filtered
        // shadow database is excluded and each table is described once
        var columnsQuery = """
                SELECT c.table_name, c.column_name, c.column_type, c.is_nullable
                FROM information_schema.columns c
                JOIN information_schema.tables t
                  ON t.table_schema = c.table_schema
                 AND t.table_name = c.table_name
                WHERE c.table_schema = 'demo'
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
                        .append(" ").append(rs.getString("column_type"));
                if ("NO".equals(rs.getString("is_nullable"))) {
                    schema.append(" NOT NULL");
                }
            }
            if (currentTable != null) {
                schema.append(");\n\n");
            }

            schema.append("""
                NOTES:
                - This is a MySQL-compatible database. The connection enables the ANSI_QUOTES SQL mode, so identifiers may be double-quoted (or backtick-quoted); string literals must ALWAYS use single quotes, never double quotes
                - The "MONTH" column of the sales table is an upper-case identifier and must always be quoted
                - Do NOT use reserved words like VALUE, KEY, ORDER, etc. as column aliases. Use descriptive names instead (e.g. total_revenue, sale_count)
                - All tables support standard SQL SELECT queries
                - sales table: use month_order column for chronological sorting (ORDER BY month_order)
                - website_traffic table: both columns are 0-based indices. day_of_week: 0=Monday, 1=Tuesday, 2=Wednesday, 3=Thursday, 4=Friday. hour_of_day: 0=9am, 1=10am, ..., 7=4pm. Use xAxis/yAxis categories in configuration to set the display labels.
                - GLOBAL DASHBOARD FILTERS: the dashboard has global filters (date range, regions) that are applied automatically by the database layer. The tables already contain only the rows matching the user's current filters. Do NOT add your own WHERE conditions for date ranges or regions to compensate or replicate these global filters; write queries as if the tables held exactly the data the user wants to see. Only add such conditions when the user explicitly asks for additional filtering in their request.
                - Always reference tables by their plain unqualified names (e.g. sales, NOT demo.sales), otherwise the global dashboard filters will not apply
                """);

        } catch (SQLException e) {
            return "Error retrieving database schema: " + e.getMessage();
        }

        return schema.toString();
    }

    private static String quoted(String identifier) {
        // Quote identifiers that aren't simple lower-case names; the
        // connection's ANSI_QUOTES mode makes double quotes valid for this
        return identifier.matches("[a-z_][a-z0-9_]*") ? identifier
                : "\"" + identifier + "\"";
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
     * effective for any SQL subsequently executed on {@code conn}: the
     * connection's default database becomes the {@code filtered} shadow
     * database, where unqualified table names resolve to filtered views (or
     * unfiltered passthrough views for tables the filters don't apply to),
     * and the views read this instance's filter values from session user
     * variables; an unset variable is NULL, i.e. "no filter". The ANSI_QUOTES
     * SQL mode is enabled so double-quoted identifiers work like in the other
     * database flavors. All of these settings are scoped to the given
     * connection, so concurrent sessions with different filter values never
     * interfere.
     */
    private void applyFiltersToConnection(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement()) {
            stmt.execute(
                    "SET SESSION sql_mode = CONCAT(@@sql_mode, ',ANSI_QUOTES')");
        }
        conn.setCatalog("filtered");
        try (var stmt = conn.prepareStatement(
                "SET @from_date = ?, @to_date = ?, @regions = ?")) {
            stmt.setString(1, fromDate == null ? null : fromDate.toString());
            stmt.setString(2, toDate == null ? null : toDate.toString());
            stmt.setString(3, regions == null ? null : toJsonArray(regions));
            stmt.execute();
        }
    }

    private static String toJsonArray(Set<String> values) {
        // JSON array literal, e.g. ["North","South"], for JSON_CONTAINS
        return values.stream()
                .map(value -> "\"" + value.replace("\\", "\\\\")
                        .replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }
}
