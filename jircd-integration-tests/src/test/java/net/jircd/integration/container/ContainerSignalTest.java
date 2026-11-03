/*
 * Copyright 2026 Guillermo Castro
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package net.jircd.integration.container;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.jircd.integration.Polling;
import net.jircd.integration.RawIrcClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec 016 User Story 2 against the built image: signals from the container runtime reach the
 * server (it is PID 1), HUP reloads without dropping anyone, and a stop tells every client why.
 */
@Tag("container")
class ContainerSignalTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Duration STOP_GRACE = Duration.ofSeconds(10);
    private static final String SHUTDOWN_ERROR =
            "^ERROR :Closing Link: \\S+ \\(Server shutting down\\)$";
    private static final String MANY_CLIENTS = "maxConnectionsPerIp: 1000\n";

    @TempDir Path dir;

    private static void assertStillConnected(RawIrcClient client) throws IOException {
        client.send("PING :alive");
        assertThat(client.readUntil("PONG", TIMEOUT)).endsWith("alive");
    }

    @Test
    void hupAppliesLoggingAndCapabilityChangesWhileClientsStayConnected() throws Exception {
        Path logDir = ContainerFixture.writableDirectory(dir.resolve("log"));
        try (JircdContainer jircd =
                        JircdContainer.startWithConfigVolume(
                                ContainerFixture.files(""), Map.of(logDir, "/var/log/jircd"));
                RawIrcClient client = RawIrcClient.connectPlaintext(jircd.plaintextPort())) {
            client.registerAndAwaitWelcome("stays", "stays");

            jircd.replaceConfigFile(
                    "jircd.yaml",
                    ContainerFixture.baseYaml(
                            """
                            capabilities:
                              message-tags: enabled
                            logging:
                              file: /var/log/jircd/jircd.log
                            """));
            jircd.signal("HUP");

            ContainerContractTest.awaitLogs(jircd, "Configuration reloaded via SIGHUP");
            assertStillConnected(client);
            client.send("CAP LS 302");
            assertThat(client.readUntil("CAP", TIMEOUT)).contains("message-tags");
            awaitFile(logDir.resolve("jircd.log"), "Configuration reloaded via SIGHUP");
        }
    }

    @Test
    void hupWithAnInvalidConfigurationKeepsTheRunningOne() throws Exception {
        try (JircdContainer jircd =
                        JircdContainer.startWithConfigVolume(ContainerFixture.files(""), Map.of());
                RawIrcClient client = RawIrcClient.connectPlaintext(jircd.plaintextPort())) {
            client.registerAndAwaitWelcome("kept", "kept");

            jircd.replaceConfigFile(
                    "jircd.yaml", ContainerFixture.baseYaml("logging:\n  console: false\n"));
            jircd.signal("HUP");

            String logs = ContainerContractTest.awaitLogs(jircd, "SIGHUP reload failed");
            // Rejected for the configuration itself, not because the file could not be read.
            assertThat(logs)
                    .contains("logging: console is off and no file is set")
                    .contains("(configuration file: " + ContainerFixture.CONFIG_PATH + ")");
            assertStillConnected(client);
            try (RawIrcClient late = RawIrcClient.connectPlaintext(jircd.plaintextPort())) {
                late.registerAndAwaitWelcome("late", "late");
            }
        }
    }

    // Every client is closed in the finally block, not in the scope that opened it.
    @SuppressWarnings("PMD.CloseResource")
    @Test
    void stopWith100ClientsExitsZeroInTimeAndEveryClientIsToldWhy() throws Exception {
        ContainerFixture.write(dir.resolve("etc"), MANY_CLIENTS);
        List<RawIrcClient> clients = new ArrayList<>();
        try (JircdContainer jircd = JircdContainer.start(dir.resolve("etc"))) {
            List<CompletableFuture<List<String>>> received = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                RawIrcClient client = RawIrcClient.connectPlaintext(jircd.plaintextPort());
                clients.add(client);
                client.registerAndAwaitWelcome("c" + i, "c" + i);
                received.add(readToEnd(client));
            }

            JircdContainer.Stopped stopped = jircd.stop(STOP_GRACE);
            System.out.printf(
                    "ContainerSignalTest: stop with 100 clients took %d ms, exit %d%n",
                    stopped.elapsed().toMillis(), stopped.exitCode());

            assertThat(stopped.exitCode()).isZero();
            assertThat(stopped.elapsed()).isLessThan(STOP_GRACE);
            for (CompletableFuture<List<String>> lines : received) {
                assertThat(lines.get(10, TimeUnit.SECONDS))
                        .last()
                        .asString()
                        .matches(SHUTDOWN_ERROR);
            }
        } finally {
            for (RawIrcClient client : clients) client.close();
        }
    }

    @Test
    void interruptBehavesLikeTerm() throws Exception {
        ContainerFixture.write(dir.resolve("etc"), "");
        try (JircdContainer jircd = JircdContainer.start(dir.resolve("etc"));
                RawIrcClient client = RawIrcClient.connectPlaintext(jircd.plaintextPort())) {
            client.registerAndAwaitWelcome("intr", "intr");
            CompletableFuture<List<String>> received = readToEnd(client);

            jircd.signal("INT");
            JircdContainer.Stopped stopped = jircd.awaitExit(STOP_GRACE);

            assertThat(stopped.exitCode()).isZero();
            assertThat(received.get(10, TimeUnit.SECONDS))
                    .last()
                    .asString()
                    .matches(SHUTDOWN_ERROR);
        }
    }

    private static CompletableFuture<List<String>> readToEnd(RawIrcClient client) {
        CompletableFuture<List<String>> lines = new CompletableFuture<>();
        Thread.ofVirtual()
                .start(
                        () -> {
                            List<String> seen = new ArrayList<>();
                            try {
                                String line;
                                while ((line = client.readLine()) != null) seen.add(line);
                            } catch (IOException _) {
                                // A reset after the last line still ends the stream.
                            }
                            lines.complete(seen);
                        });
        return lines;
    }

    private static void awaitFile(Path file, String expected) throws Exception {
        Polling.until(TIMEOUT, () -> ContainerContractTest.fileContains(file, expected));
        assertThat(Files.exists(file) ? Files.readString(file) : "<no file>").contains(expected);
    }
}
