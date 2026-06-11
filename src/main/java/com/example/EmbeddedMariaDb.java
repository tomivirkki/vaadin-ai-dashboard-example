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
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Starts a private MariaDB server — a drop-in, wire- and SQL-compatible MySQL
 * replacement — as a child process with its own temporary data directory and
 * port, so the demo needs no Docker and no managed database service. The
 * MariaDB/MySQL server binaries must be installed on the machine (e.g.
 * {@code sudo apt-get install mariadb-server}); everything else is handled
 * here. The server is stopped on JVM exit.
 */
final class EmbeddedMariaDb {

    private static final List<String> INSTALL_DB_CANDIDATES = List.of(
            "mariadb-install-db", "/usr/bin/mariadb-install-db",
            "mysql_install_db", "/usr/bin/mysql_install_db");
    private static final List<String> SERVER_CANDIDATES = List.of("mariadbd",
            "/usr/sbin/mariadbd", "mysqld", "/usr/sbin/mysqld",
            "/usr/local/mysql/bin/mysqld");

    private EmbeddedMariaDb() {
    }

    /**
     * Initializes a fresh data directory and starts the server on a free
     * port, blocking until it accepts connections.
     *
     * @return the base JDBC URL of the started server, with no database
     *         selected
     */
    static String start() {
        try {
            var dataDir = Files.createTempDirectory("embedded-mariadb");
            int port = freePort();
            initializeDataDirectory(dataDir);
            var server = startServer(dataDir, port);
            Runtime.getRuntime().addShutdownHook(new Thread(server::destroy));
            var url = "jdbc:mysql://127.0.0.1:" + port
                    + "/?user=root&sslMode=DISABLED&allowPublicKeyRetrieval=true";
            waitUntilReady(url, server, dataDir);
            return url;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to start embedded MariaDB",
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while starting embedded MariaDB", e);
        }
    }

    private static void initializeDataDirectory(Path dataDir)
            throws IOException, InterruptedException {
        var installLog = dataDir.resolve("install.log").toFile();
        var process = new ProcessBuilder(findBinary(INSTALL_DB_CANDIDATES),
                "--no-defaults", "--datadir=" + dataDir,
                "--auth-root-authentication-method=normal", "--skip-test-db")
                .redirectOutput(installLog).redirectErrorStream(true).start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException(
                    "Initializing the MariaDB data directory failed, see "
                            + installLog);
        }
    }

    private static Process startServer(Path dataDir, int port)
            throws IOException {
        return new ProcessBuilder(findBinary(SERVER_CANDIDATES),
                "--no-defaults", "--datadir=" + dataDir, "--port=" + port,
                "--bind-address=127.0.0.1",
                "--socket=" + dataDir.resolve("mysql.sock"))
                .redirectOutput(dataDir.resolve("server.log").toFile())
                .redirectErrorStream(true).start();
    }

    private static void waitUntilReady(String url, Process server,
            Path dataDir) throws InterruptedException {
        for (int attempt = 0; attempt < 120; attempt++) {
            if (!server.isAlive()) {
                throw new IllegalStateException(
                        "Embedded MariaDB exited during startup, see "
                                + dataDir.resolve("server.log"));
            }
            try (var ignored = DriverManager.getConnection(url)) {
                return;
            } catch (SQLException e) {
                TimeUnit.MILLISECONDS.sleep(250);
            }
        }
        throw new IllegalStateException(
                "Embedded MariaDB did not accept connections in time, see "
                        + dataDir.resolve("server.log"));
    }

    private static String findBinary(List<String> candidates) {
        for (var candidate : candidates) {
            if (candidate.indexOf('/') >= 0) {
                var path = Paths.get(candidate);
                if (Files.isExecutable(path)) {
                    return candidate;
                }
            } else {
                for (var dir : System.getenv("PATH").split(":")) {
                    if (!dir.isEmpty()
                            && Files.isExecutable(Paths.get(dir, candidate))) {
                        return candidate;
                    }
                }
            }
        }
        throw new IllegalStateException("No MariaDB/MySQL server binaries "
                + "found. Install them first, e.g. with "
                + "'sudo apt-get install mariadb-server'. Tried: "
                + candidates);
    }

    private static int freePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
