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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import net.jircd.integration.Polling;
import net.jircd.integration.RawIrcClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec 016 User Story 1 against the built image: an operator mounts a configuration and gets a
 * working server, logging to standard output, as uid 65532, with nothing written in the container
 * (every container here runs with a read-only root filesystem).
 */
@Tag("container")
class ContainerContractTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @TempDir Path dir;

    @Test
    void plaintextAndTlsClientsChat() throws Exception {
        ContainerFixture.write(dir.resolve("etc"), "");
        try (JircdContainer jircd = JircdContainer.start(dir.resolve("etc"));
                RawIrcClient alice = RawIrcClient.connectPlaintext(jircd.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectTls(jircd.tlsPort())) {
            alice.registerAndAwaitWelcome("alice", "alice");
            bob.registerAndAwaitWelcome("bob", "bob");
            alice.send("JOIN #lobby");
            alice.readUntil("366", TIMEOUT);
            bob.send("JOIN #lobby");
            bob.readUntil("366", TIMEOUT);

            alice.send("PRIVMSG #lobby :hello from plaintext");
            assertThat(bob.readUntil("hello from plaintext", TIMEOUT))
                    .startsWith(":alice!")
                    .endsWith("PRIVMSG #lobby :hello from plaintext");
            bob.send("PRIVMSG #lobby :hello over TLS");
            assertThat(alice.readUntil("hello over TLS", TIMEOUT)).startsWith(":bob!");
        }
    }

    @Test
    void logsGoToStandardOutputAndNothingIsWrittenInsideTheContainer() throws Exception {
        ContainerFixture.write(dir.resolve("etc"), "");
        try (JircdContainer jircd = JircdContainer.start(dir.resolve("etc"))) {
            try (RawIrcClient client = RawIrcClient.connectPlaintext(jircd.plaintextPort())) {
                client.registerAndAwaitWelcome("logged", "logged");
            }
            String logs = awaitLogs(jircd, "connection-event=disconnected");
            assertThat(logs)
                    .contains("jircd started as " + ContainerFixture.SERVER_NAME)
                    .contains("Plaintext listener started on port 6667")
                    .contains("TLS listener started on port 6697")
                    .contains("connection-event=connected")
                    .doesNotContainIgnoringCase("read-only file system")
                    .doesNotContain(" ERROR ")
                    .doesNotContain(" WARN ");
        }
    }

    @Test
    void runsAsUid65532() throws Exception {
        ContainerFixture.write(dir.resolve("etc"), "");
        try (JircdContainer jircd = JircdContainer.start(dir.resolve("etc"))) {
            assertThat(jircd.configuredUser()).isEqualTo("65532:65532");
            assertThat(jircd.processUids()).isNotEmpty().allMatch("65532"::equals);
        }
    }

    @Test
    void aLogFileOnAMountedVolumeReceivesLinesAsWellAsStandardOutput() throws Exception {
        Path logDir = ContainerFixture.writableDirectory(dir.resolve("log"));
        ContainerFixture.write(dir.resolve("etc"), "logging:\n  file: /var/log/jircd/jircd.log\n");
        try (JircdContainer jircd =
                JircdContainer.start(dir.resolve("etc"), Map.of(logDir, "/var/log/jircd"))) {
            try (RawIrcClient client = RawIrcClient.connectPlaintext(jircd.plaintextPort())) {
                client.registerAndAwaitWelcome("volume", "volume");
            }
            Path file = logDir.resolve("jircd.log");
            awaitFile(file, "connection-event=connected");
            assertThat(Files.readString(file)).contains("jircd started as");
            assertThat(jircd.logs()).contains("connection-event=connected");
        }
    }

    static String awaitLogs(JircdContainer jircd, String expected) throws InterruptedException {
        Polling.until(TIMEOUT, () -> jircd.logs().contains(expected));
        String logs = jircd.logs();
        assertThat(logs).contains(expected);
        return logs;
    }

    static boolean fileContains(Path file, String expected) {
        try {
            return Files.exists(file) && Files.readString(file).contains(expected);
        } catch (java.io.IOException _) {
            return false;
        }
    }

    private static void awaitFile(Path file, String expected) throws Exception {
        Polling.until(TIMEOUT, () -> fileContains(file, expected));
        assertThat(Files.exists(file) ? Files.readString(file) : "<no file>").contains(expected);
    }
}
