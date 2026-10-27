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
package net.jircd.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #77: MOTD, ISON, PASS, ADMIN, STATS and HELP/HELPOP over real sockets. */
class InformationalCommandsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @TempDir Path dir;

    private static String codes(List<String> lines) {
        return String.join(
                " ",
                lines.stream()
                        .map(l -> l.split(" "))
                        .filter(p -> p.length > 1 && p[1].matches("\\d{3}"))
                        .map(p -> p[1])
                        .toList());
    }

    @Test
    void passBeforeRegistrationIsIgnoredAndMotdFileIsServedInTheBurst() throws Exception {
        Path motd = dir.resolve("motd.txt");
        Files.writeString(motd, "Hello there\nSecond line\n");
        try (TestServer server = TestServer.start("motdPath: \"" + motd + "\"\n");
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.send("PASS hunter2");
            alice.send("NICK alice");
            alice.send("USER alice 0 * :Alice");
            List<String> beforeMotd = new java.util.ArrayList<>();
            String line;
            do {
                line = alice.readLine();
                beforeMotd.add(line);
            } while (!line.contains(" 375 "));
            assertThat(beforeMotd).noneMatch(l -> l.matches("\\S+ 4\\d\\d .*"));
            assertThat(alice.readLine()).endsWith(" 372 alice :- Hello there");
            assertThat(alice.readLine()).endsWith(" 372 alice :- Second line");
            assertThat(alice.readLine()).endsWith(" 376 alice :End of /MOTD command");

            alice.send("MOTD");
            assertThat(codes(alice.readThroughPong(TIMEOUT))).isEqualTo("375 372 372 376");
        }
    }

    @Test
    void withoutAMotdPathBothRegistrationAndMotdAnswer422() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome("alice", "alice");
            assertThat(alice.readUntil(" 422 ", TIMEOUT))
                    .endsWith(" 422 alice :MOTD File is missing");

            alice.send("MOTD");
            assertThat(alice.readUntil(" 422 ", TIMEOUT))
                    .endsWith(" 422 alice :MOTD File is missing");
        }
    }

    @Test
    void aMissingMotdFileIsAnswered422AndTheServerKeepsRunning() throws Exception {
        try (TestServer server =
                        TestServer.start("motdPath: \"" + dir.resolve("absent.txt") + "\"\n");
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome("alice", "alice");
            alice.readUntil(" 422 ", TIMEOUT);
            alice.send("PING still-here");
            assertThat(alice.readUntil("PONG", TIMEOUT)).contains("still-here");
        }
    }

    @Test
    void reloadingPicksUpANewMotdPath() throws Exception {
        Path motd = dir.resolve("motd.txt");
        Files.writeString(motd, "reloaded text");
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome("alice", "alice");
            alice.readUntil(" 422 ", TIMEOUT);

            Files.writeString(
                    server.configPath,
                    Files.readString(server.configPath) + "motdPath: \"" + motd + "\"\n");
            server.application.reloader().reload();

            alice.send("MOTD");
            assertThat(alice.readUntil(" 372 ", TIMEOUT)).endsWith(" 372 alice :- reloaded text");
        }
    }

    @Test
    void isonListsOnlineNicknamesCaseInsensitively() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome("Alice", "alice");
            bob.registerAndAwaitWelcome("Bob", "bob");

            alice.send("ISON bob nobody ALICE");
            assertThat(alice.readUntil(" 303 ", TIMEOUT)).endsWith(" 303 Alice :Bob Alice");
        }
    }

    @Test
    void passAfterRegistrationAndWithoutParametersIsRejected() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome("alice", "alice");

            alice.send("PASS again");
            assertThat(alice.readUntil(" 462 ", TIMEOUT))
                    .endsWith(" 462 alice :You may not reregister");
            alice.send("PASS");
            assertThat(alice.readUntil(" 461 ", TIMEOUT))
                    .endsWith(" 461 alice PASS :Not enough parameters");
        }
    }

    @Test
    void adminRepliesFromTheConfiguredContact() throws Exception {
        try (TestServer server =
                        TestServer.start(
                                "adminName: Jane Admin\nadminLocation: Example Org\nadminEmail: jane@example.org\n");
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome("alice", "alice");
            alice.send("ADMIN");
            assertThat(alice.readUntil(" 256 ", TIMEOUT))
                    .endsWith(
                            " 256 alice test.jircd.local :Administrative info about test.jircd.local");
            assertThat(alice.readLine()).endsWith(" 257 alice :Jane Admin");
            assertThat(alice.readLine()).endsWith(" 258 alice :Example Org");
            assertThat(alice.readLine()).endsWith(" 259 alice jane@example.org");
        }
    }

    @Test
    void adminWithoutAContactAnswers423() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome("alice", "alice");
            alice.send("ADMIN");
            assertThat(alice.readUntil(" 423 ", TIMEOUT))
                    .endsWith(" 423 alice test.jircd.local :No administrative info available");
        }
    }

    @Test
    void statsUReportsUptimeAndOtherLettersOnlyTheEndMarker() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome("alice", "alice");

            alice.send("STATS u");
            assertThat(alice.readUntil(" 242 ", TIMEOUT)).contains(":Server Up 0 days 0:00:");
            assertThat(alice.readLine()).endsWith(" 219 alice u :End of /STATS report");

            alice.send("STATS m");
            assertThat(alice.readUntil(" 219 ", TIMEOUT))
                    .endsWith(" 219 alice m :End of /STATS report");

            alice.send("STATS");
            assertThat(alice.readUntil(" 461 ", TIMEOUT)).contains("STATS");
        }
    }

    @Test
    void helpAndHelpopServeTopicsAndRejectUnknownOnes() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome("alice", "alice");

            alice.send("HELP");
            assertThat(alice.readUntil(" 704 ", TIMEOUT)).contains(" 704 alice index :");
            assertThat(alice.readUntil(" 706 ", TIMEOUT))
                    .endsWith(" 706 alice index :End of /HELP");

            alice.send("HELPOP stats");
            assertThat(alice.readUntil(" 704 ", TIMEOUT)).contains(" 704 alice STATS :");
            assertThat(alice.readUntil(" 705 ", TIMEOUT)).contains(" 705 alice STATS :");
            alice.readUntil(" 706 ", TIMEOUT);

            alice.send("HELP nonsense");
            assertThat(alice.readUntil(" 524 ", TIMEOUT))
                    .endsWith(" 524 alice nonsense :Help not found");
        }
    }

    @Test
    void theNewCommandsAreRefusedBeforeRegistrationExceptPass() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            client.send("MOTD");
            client.send("ISON a");
            client.send("PASS x");
            assertThat(codes(client.readThroughPong(TIMEOUT))).isEqualTo("451 451");
        }
    }
}
