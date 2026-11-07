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
package net.jircd.integration.nativeimage;

import static org.assertj.core.api.Assertions.assertThat;

import com.password4j.Password;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import net.jircd.integration.Polling;
import net.jircd.integration.RawIrcClient;
import net.jircd.integration.TestServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the installed server over sockets while it runs under the GraalVM tracing agent, so the
 * agent records everything the native executable must keep: registration on a plaintext, a PEM and
 * a PKCS12 listener, Argon2 and bcrypt {@code OPER}, every capability and extension, a logging
 * file, a successful and a rejected reload, and a graceful shutdown (spec 016 T037). Run only by
 * {@code ./gradlew :jircd-server:collectNativeMetadata}, on a GraalVM JDK; not part of {@code
 * test}.
 */
@Tag("native-metadata")
class NativeMetadataScenariosTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @TempDir Path dir;

    @Test
    void exercisesTheServerUnderTheAgent() throws Exception {
        String lib = requiredProperty("jircd.server.lib");
        String agentDir = requiredProperty("jircd.agent.dir");
        Path javaBinary = Path.of(System.getProperty("java.home"), "bin", "java");
        int plain = freePort();
        int pem = freePort();
        int pkcs12 = freePort();
        Path log = dir.resolve("server.out");
        Path config = dir.resolve("jircd.yaml");
        Files.writeString(config, yaml(plain, pem, pkcs12, false));

        Process server =
                new ProcessBuilder(
                                javaBinary.toString(),
                                "-agentlib:native-image-agent=config-merge-dir=" + agentDir,
                                "-cp",
                                lib + "/*",
                                "net.jircd.server.JircdServerApplication",
                                config.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
        try {
            assertThat(Polling.until(TIMEOUT, () -> logContains(log, "jircd started as")))
                    .as("server start, log:%n%s", read(log))
                    .isTrue();
            scenarios(plain, pem, pkcs12);

            // Reload: a valid change, then one the loader rejects.
            Files.writeString(config, yaml(plain, pem, pkcs12, true));
            hup(server);
            assertThat(Polling.until(TIMEOUT, () -> logContains(log, "reloaded via SIGHUP")))
                    .as("reload, log:%n%s", read(log))
                    .isTrue();
            Files.writeString(config, "serverName: x\nlogging:\n  console: false\n");
            hup(server);
            assertThat(Polling.until(TIMEOUT, () -> logContains(log, "SIGHUP reload failed")))
                    .as("rejected reload, log:%n%s", read(log))
                    .isTrue();

            // Shutdown with a client connected, so the "Closing Link" path runs.
            try (RawIrcClient last = RawIrcClient.connectPlaintext(plain)) {
                last.registerAndAwaitWelcome("last", "last");
                server.destroy(); // SIGTERM
                assertThat(server.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                        .as("exit after TERM, log:%n%s", read(log))
                        .isTrue();
            }
            assertThat(server.exitValue()).isZero();
        } finally {
            server.destroyForcibly();
        }
    }

    private static void scenarios(int plain, int pem, int pkcs12) throws Exception {
        try (RawIrcClient alice = RawIrcClient.connectPlaintext(plain);
                RawIrcClient bob = RawIrcClient.connectTls(pem);
                RawIrcClient carol = RawIrcClient.connectTls(pkcs12)) {
            cmd(alice, "CAP LS 302");
            alice.readUntil("CAP", TIMEOUT);
            cmd(
                    alice,
                    "CAP REQ :away-notify batch echo-message message-tags multi-prefix"
                            + " server-time userhost-in-names");
            alice.readUntil("ACK", TIMEOUT);
            cmd(alice, "CAP END");
            alice.registerAndAwaitWelcome("alice", "alice");
            bob.registerAndAwaitWelcome("bob", "bob");
            carol.registerAndAwaitWelcome("carol", "carol");

            cmd(alice, "JOIN #native");
            alice.readUntil("366", TIMEOUT);
            cmd(bob, "JOIN #native");
            bob.readUntil("366", TIMEOUT);
            cmd(alice, "PRIVMSG #native :hello");
            bob.readUntil("hello", TIMEOUT);
            cmd(alice, "TOPIC #native :a topic");
            cmd(alice, "MODE #native +t");
            cmd(alice, "NAMES #native");
            cmd(alice, "WHO #native");
            cmd(alice, "WHOIS bob");
            cmd(alice, "LIST");
            cmd(alice, "AWAY :gone");
            cmd(alice, "AWAY");
            cmd(alice, "PRIVMSG bob :direct");
            bob.readUntil("direct", TIMEOUT);
            cmd(alice, "NOTICE bob :notice");
            cmd(alice, "VERSION");
            cmd(alice, "TIME");
            cmd(alice, "MOTD");
            cmd(alice, "LUSERS");
            cmd(alice, "INFO");
            cmd(alice, "PING barrier");
            alice.readUntil("PONG", TIMEOUT);

            // OPER: Argon2 and bcrypt hashes, a right and a wrong password.
            cmd(alice, "OPER argon-admin :" + TestServer.ADMIN_PASSWORD);
            alice.readUntil("381", TIMEOUT);
            cmd(alice, "WALLOPS :checking");
            cmd(bob, "OPER bcrypt-admin :" + TestServer.ADMIN_PASSWORD);
            bob.readUntil("381", TIMEOUT);
            cmd(carol, "OPER argon-admin wrong-password");
            carol.readUntil("464", TIMEOUT);
            cmd(alice, "EXTENSION");
            cmd(alice, "REHASH");
            cmd(alice, "PING barrier");
            alice.readUntil("PONG", TIMEOUT);

            cmd(carol, "PART #native");
            cmd(carol, "QUIT :bye");
            cmd(bob, "QUIT :bye");
        }
    }

    /** Sends one line, paced under the per-connection rate limit (20 tokens, 10 per second). */
    private static void cmd(RawIrcClient client, String line)
            throws IOException, InterruptedException {
        client.send(line);
        Polling.pause(Duration.ofMillis(120));
    }

    private String yaml(int plain, int pem, int pkcs12, boolean changed) {
        Path pemCert = resource("tls/test-fullchain.pem");
        Path pemKey = resource("tls/test-privkey.pem");
        Path keystore = resource("tls/test-keystore.p12");
        Path logFile = dir.resolve("jircd.log");
        return "serverName: native.jircd.local\n"
                + "listeners:\n"
                + "  - port: "
                + plain
                + "\n    bindAddress: 127.0.0.1\n    tls: false\n"
                + "  - port: "
                + pem
                + "\n    bindAddress: 127.0.0.1\n    tls: true\n    certPath: \""
                + pemCert
                + "\"\n    keyPath: \""
                + pemKey
                + "\"\n"
                + "  - port: "
                + pkcs12
                + "\n    bindAddress: 127.0.0.1\n    tls: true\n    keystorePath: \""
                + keystore
                + "\"\n    keystorePassword: \"changeit\"\n"
                + "capabilities:\n"
                + "  away-notify: enabled\n  batch: enabled\n  echo-message: enabled\n"
                + "  message-tags: enabled\n  multi-prefix: enabled\n"
                + "  server-time: enabled\n"
                + "  userhost-in-names: enabled\n"
                + "server-extensions:\n  admin: enabled\n  cloak: enabled\n"
                + "administratorCredentials:\n"
                + "  - username: argon-admin\n    hashedPassword: \""
                + Password.hash(TestServer.ADMIN_PASSWORD).withArgon2().getResult()
                + "\"\n"
                + "  - username: bcrypt-admin\n    hashedPassword: \""
                + Password.hash(TestServer.ADMIN_PASSWORD).withBcrypt().getResult()
                + "\"\n"
                + "logging:\n  console: true\n  file: \""
                + logFile
                + "\"\n"
                + (changed ? "maxConnections: 500\n" : "");
    }

    private static Path resource(String name) {
        return Path.of(TestServer.resourcePath(name));
    }

    private static void hup(Process server) throws IOException, InterruptedException {
        Process kill = new ProcessBuilder("kill", "-HUP", Long.toString(server.pid())).start();
        assertThat(kill.waitFor()).isZero();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static boolean logContains(Path log, String text) {
        return read(log).contains(text);
    }

    private static String read(Path log) {
        try {
            return Files.exists(log) ? Files.readString(log) : "";
        } catch (IOException _) {
            return "";
        }
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("set -D" + name);
        }
        return value;
    }
}
