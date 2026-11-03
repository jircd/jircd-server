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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.jircd.core.config.ConfigurationException;
import net.jircd.core.config.ServerConfiguration;
import net.jircd.server.logging.LoggingSettingsApplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * The {@code logging} section on a running server (spec 016 FR-006): the test JVM's logback is
 * bootstrapped by jircd's own configurator, as in production, so the server's settings apply to it.
 */
class LoggingConfigurationTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @TempDir Path dir;

    private static LoggerContext context() {
        return (LoggerContext) LoggerFactory.getILoggerFactory();
    }

    private static List<Appender<ILoggingEvent>> rootAppenders() {
        List<Appender<ILoggingEvent>> appenders = new ArrayList<>();
        context()
                .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
                .iteratorForAppenders()
                .forEachRemaining(appenders::add);
        return appenders;
    }

    @AfterEach
    void restoreTheDefaultForOtherTests() {
        new LoggingSettingsApplier(context()).apply(ServerConfiguration.Logging.DEFAULT);
    }

    private static String fileYaml(Path file) {
        return "logging:\n  file: \"" + file + "\"\n";
    }

    private static void awaitContent(Path file, String expected) throws Exception {
        Polling.until(WAIT, () -> fileContains(file, expected));
        assertThat(Files.exists(file) ? Files.readString(file) : "<no file>").contains(expected);
    }

    private static boolean fileContains(Path file, String expected) {
        try {
            return Files.exists(file) && Files.readString(file).contains(expected);
        } catch (java.io.IOException _) {
            return false;
        }
    }

    private static void connectAndRegister(TestServer server, String nick) throws Exception {
        try (RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            client.registerAndAwaitWelcome(nick, nick);
        }
    }

    @Test
    void withoutALoggingSectionTheServerLogsToTheConsoleOnly() throws Exception {
        try (TestServer server = TestServer.start()) {
            connectAndRegister(server, "plain");
            assertThat(rootAppenders()).singleElement().isInstanceOf(ConsoleAppender.class);
        }
    }

    @Test
    void aConfiguredFileReceivesConnectionEventsAlongsideTheConsole() throws Exception {
        Path file = dir.resolve("jircd.log");
        try (TestServer server = TestServer.start(fileYaml(file))) {
            connectAndRegister(server, "filed");
            awaitContent(file, "connection-event=connected");
            assertThat(rootAppenders())
                    .hasSize(2)
                    .hasAtLeastOneElementOfType(ConsoleAppender.class)
                    .hasAtLeastOneElementOfType(RollingFileAppender.class);
        }
    }

    @Test
    void consoleOffSendsEverythingToTheFileOnly() throws Exception {
        Path file = dir.resolve("only.log");
        try (TestServer server =
                TestServer.start("logging:\n  console: false\n  file: \"" + file + "\"\n")) {
            connectAndRegister(server, "quiet");
            awaitContent(file, "connection-event=connected");
            assertThat(rootAppenders()).singleElement().isInstanceOf(RollingFileAppender.class);
        }
    }

    @Test
    void underAnExternalLogbackConfigurationTheServerStartsAndLeavesTheSectionAlone()
            throws Exception {
        Path missing = dir.resolve("absent-dir").resolve("jircd.log");
        String previous = System.setProperty("logback.configurationFile", "/etc/custom.xml");
        try (TestServer server = TestServer.start(fileYaml(missing))) {
            connectAndRegister(server, "external");
            assertThat(missing).doesNotExist();
            assertThat(rootAppenders()).singleElement().isInstanceOf(ConsoleAppender.class);
        } finally {
            if (previous == null) {
                System.clearProperty("logback.configurationFile");
            } else {
                System.setProperty("logback.configurationFile", previous);
            }
        }
    }

    // --- Reload (FR-006b): SIGHUP and REHASH both go through ConfigurationReloader. ---

    private static void rewrite(TestServer server, String extraYaml) throws Exception {
        Files.writeString(server.configPath, TestServer.baseYaml() + extraYaml);
    }

    @Test
    void aReloadThatAddsAFileStartsWritingIt() throws Exception {
        Path file = dir.resolve("added.log");
        try (TestServer server = TestServer.start()) {
            rewrite(server, fileYaml(file));
            server.application.reloader().reloadWithOutcome();

            connectAndRegister(server, "after");
            awaitContent(file, "connection-event=connected");
            assertThat(rootAppenders()).hasSize(2);
        }
    }

    @Test
    void aReloadThatMovesTheFileWritesOnlyTheNewOneAfterwards() throws Exception {
        Path first = dir.resolve("first.log");
        Path second = dir.resolve("second.log");
        try (TestServer server = TestServer.start(fileYaml(first))) {
            connectAndRegister(server, "before");
            awaitContent(first, "connection-event=connected");

            rewrite(server, fileYaml(second));
            server.application.reloader().reloadWithOutcome();
            String firstAtSwitch = Files.readString(first);
            connectAndRegister(server, "moved");

            awaitContent(second, "connection-event=connected");
            assertThat(Files.readString(first)).isEqualTo(firstAtSwitch);
            assertThat(rootAppenders())
                    .filteredOn(RollingFileAppender.class::isInstance)
                    .singleElement()
                    .satisfies(
                            a ->
                                    assertThat(((RollingFileAppender<?>) a).getFile())
                                            .isEqualTo(second.toString()));
        }
    }

    @Test
    void aReloadCanTurnTheConsoleOffWhileAFileIsSet() throws Exception {
        Path file = dir.resolve("kept.log");
        try (TestServer server = TestServer.start(fileYaml(file))) {
            rewrite(server, "logging:\n  console: false\n  file: \"" + file + "\"\n");
            server.application.reloader().reloadWithOutcome();

            assertThat(rootAppenders()).singleElement().isInstanceOf(RollingFileAppender.class);
        }
    }

    @Test
    void aRejectedLoggingSectionRejectsTheWholeReload() throws Exception {
        Path file = dir.resolve("stays.log");
        try (TestServer server = TestServer.start(fileYaml(file))) {
            List<Appender<ILoggingEvent>> before = rootAppenders();
            int topicBefore = server.application.reloader().current().topicMaxLength();
            rewrite(server, "topicMaxLength: 50\nlogging:\n  console: false\n");

            assertThatThrownBy(() -> server.application.reloader().reloadWithOutcome())
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageStartingWith("logging: console is off and no file is set");

            assertThat(rootAppenders()).containsExactlyElementsOf(before);
            assertThat(server.application.reloader().current().topicMaxLength())
                    .isEqualTo(topicBefore);
        }
    }
}
