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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.jircd.integration.Polling;
import net.jircd.integration.RawIrcClient;
import net.jircd.integration.TestServer;
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

    /** Every capability and both server extensions, so the baseline covers all nine modules. */
    private static final String EVERYTHING_ENABLED_YAML =
            """
            capabilities:
              away-notify: enabled
              batch: enabled
              echo-message: enabled
              message-tags: enabled
              multi-prefix: enabled
              server-time: enabled
              userhost-in-names: enabled
            """
                    + TestServer.adminAndCloakEnabledYaml();

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

    @Test
    void operWithAnArgon2CredentialSucceedsAndAWrongPasswordFails() throws Exception {
        ContainerFixture.write(dir.resolve("etc"), TestServer.argon2AdminEnabledYaml());
        try (JircdContainer jircd = JircdContainer.start(dir.resolve("etc"));
                RawIrcClient wrong = RawIrcClient.connectPlaintext(jircd.plaintextPort());
                RawIrcClient right = RawIrcClient.connectPlaintext(jircd.plaintextPort())) {
            wrong.registerAndAwaitWelcome("wrong", "wrong");
            wrong.send("OPER " + TestServer.ADMIN_USERNAME + " not-the-password");
            assertThat(wrong.readUntil("464", TIMEOUT)).contains("464");

            right.registerAndAwaitWelcome("right", "right");
            right.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            assertThat(right.readUntil("381", TIMEOUT)).contains("381");
        }
    }

    @Test
    void tlsRegistrationWorksWithAPkcs12KeystoreAsWellAsPem() throws Exception {
        Path etc = dir.resolve("etc");
        ContainerFixture.writeRaw(etc, ContainerFixture.pkcs12Yaml(""));
        try (JircdContainer jircd = JircdContainer.start(etc);
                RawIrcClient client = RawIrcClient.connectTls(jircd.tlsPort())) {
            client.registerAndAwaitWelcome("keystore", "keystore");
            assertThat(client.peerCertificateFingerprint()).isNotBlank();
        }
    }

    /**
     * Both variants offer every capability and every ISUPPORT token (FR-013, FR-014). The baseline
     * was recorded from the JVM image; a native image that lost an extension fails here.
     */
    @Test
    void capabilitiesAndIsupportMatchTheRecordedJvmBaseline() throws Exception {
        ContainerFixture.write(dir.resolve("etc"), EVERYTHING_ENABLED_YAML);
        try (JircdContainer jircd = JircdContainer.start(dir.resolve("etc"));
                RawIrcClient client = RawIrcClient.connectPlaintext(jircd.plaintextPort())) {
            client.send("CAP LS 302");
            List<String> capabilities = new ArrayList<>();
            for (String line = client.readUntil("CAP", TIMEOUT); ; line = client.readLine()) {
                String list = line.substring(line.indexOf(" LS ") + 4);
                boolean more = list.startsWith("* ");
                list = more ? list.substring(2) : list;
                capabilities.addAll(tokens(list.startsWith(":") ? list.substring(1) : list));
                if (!more) break;
            }
            client.send("CAP END");
            client.send("NICK baseline");
            client.send("USER baseline 0 * :baseline");
            List<String> isupport = new ArrayList<>();
            for (String line = client.readUntil(" 005 ", TIMEOUT); ; ) {
                // ":server 005 nick TOKEN TOKEN :are supported by this server"
                String body = line.substring(line.indexOf(" 005 ") + 5);
                body = body.substring(body.indexOf(' ') + 1, body.indexOf(" :"));
                isupport.addAll(tokens(body));
                line = client.readLine();
                if (!line.contains(" 005 ")) break;
            }
            Set<String> actual = new TreeSet<>();
            capabilities.forEach(token -> actual.add("cap " + token));
            isupport.forEach(token -> actual.add("isupport " + token));
            assertThat(actual).containsExactlyInAnyOrderElementsOf(jvmBaseline());
        }
    }

    private static List<String> tokens(String list) {
        return list.isBlank() ? List.of() : List.of(list.strip().split(" +"));
    }

    /** The lines of {@code container/jvm-baseline.txt}, which the sorted result must equal. */
    private static List<String> jvmBaseline() throws IOException {
        try (InputStream in =
                ContainerContractTest.class
                        .getClassLoader()
                        .getResourceAsStream("container/jvm-baseline.txt")) {
            if (in == null) throw new IllegalStateException("Missing container/jvm-baseline.txt");
            List<String> lines =
                    new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            return new TreeSet<>(lines.stream().filter(l -> !l.isBlank()).toList())
                    .stream().toList();
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
