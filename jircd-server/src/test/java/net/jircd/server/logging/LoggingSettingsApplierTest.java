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
package net.jircd.server.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.jircd.core.config.ServerConfiguration.Logging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bootstrap configurator and the settings applier against a private {@link LoggerContext}, so
 * the global logging of the test JVM is never touched (spec 016 R1, data-model.md).
 */
class LoggingSettingsApplierTest {

    @TempDir Path dir;

    private final LoggerContext context = newContext();

    /** Production contexts get their MDC adapter from the SLF4J provider; a bare one needs it. */
    private static LoggerContext newContext() {
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
        return context;
    }

    @AfterEach
    void stopContext() {
        context.stop();
    }

    private Logger root() {
        return context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    private List<Appender<ILoggingEvent>> rootAppenders() {
        List<Appender<ILoggingEvent>> appenders = new ArrayList<>();
        for (Iterator<Appender<ILoggingEvent>> it = root().iteratorForAppenders(); it.hasNext(); ) {
            appenders.add(it.next());
        }
        return appenders;
    }

    private Configurator.ExecutionStatus bootstrap() {
        BootstrapLogbackConfigurator configurator = new BootstrapLogbackConfigurator();
        configurator.setContext(context);
        return configurator.configure(context);
    }

    private void log(String message) {
        context.getLogger("net.jircd.test").info(message);
    }

    @Test
    void bootstrapInstallsTheConsoleAtInfoWithTheFixedPattern() {
        assertThat(bootstrap()).isEqualTo(Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY);

        assertThat(root().getLevel()).isEqualTo(Level.INFO);
        assertThat(rootAppenders()).singleElement().isInstanceOf(ConsoleAppender.class);
        ConsoleAppender<ILoggingEvent> console =
                (ConsoleAppender<ILoggingEvent>) rootAppenders().getFirst();
        assertThat(console.isStarted()).isTrue();
        assertThat(((PatternLayoutEncoder) console.getEncoder()).getPattern())
                .isEqualTo(LoggingSettingsApplier.PATTERN);
    }

    @Test
    void anExternalConfigurationFileMakesTheBootstrapStepAside() {
        String previous = System.setProperty("logback.configurationFile", "/etc/custom.xml");
        try {
            assertThat(bootstrap()).isEqualTo(Configurator.ExecutionStatus.INVOKE_NEXT_IF_ANY);
            assertThat(rootAppenders()).isEmpty();
        } finally {
            if (previous == null) {
                System.clearProperty("logback.configurationFile");
            } else {
                System.setProperty("logback.configurationFile", previous);
            }
        }
    }

    @Test
    void theDefaultKeepsTheBootstrapConsoleInstance() {
        bootstrap();
        Appender<ILoggingEvent> bootstrapConsole = rootAppenders().getFirst();

        new LoggingSettingsApplier(context).apply(Logging.DEFAULT);

        assertThat(rootAppenders()).containsExactly(bootstrapConsole);
    }

    @Test
    void aFileIsAddedNextToTheConsoleWithTheFixedRotation() throws Exception {
        bootstrap();
        Path file = dir.resolve("jircd.log");

        new LoggingSettingsApplier(context).apply(new Logging(true, file));
        log("to both destinations");

        assertThat(rootAppenders()).hasSize(2).hasAtLeastOneElementOfType(ConsoleAppender.class);
        RollingFileAppender<ILoggingEvent> fileAppender =
                rootAppenders().stream()
                        .filter(RollingFileAppender.class::isInstance)
                        .map(a -> (RollingFileAppender<ILoggingEvent>) a)
                        .findFirst()
                        .orElseThrow();
        assertThat(fileAppender.getFile()).isEqualTo(file.toString());
        SizeAndTimeBasedRollingPolicy<?> policy =
                (SizeAndTimeBasedRollingPolicy<?>) fileAppender.getRollingPolicy();
        assertThat(policy.getFileNamePattern()).isEqualTo(file + ".%d{yyyy-MM-dd}.%i.gz");
        assertThat(policy.getMaxHistory()).isEqualTo(30);
        assertThat(LoggingSettingsApplier.MAX_FILE_SIZE.getSize()).isEqualTo(50L * 1024 * 1024);
        assertThat(LoggingSettingsApplier.TOTAL_SIZE_CAP.getSize()).isEqualTo(1024L * 1024 * 1024);
        assertThat(((PatternLayoutEncoder) fileAppender.getEncoder()).getPattern())
                .isEqualTo(LoggingSettingsApplier.PATTERN);
        assertThat(Files.readString(file)).contains("INFO  net.jircd.test - to both destinations");
    }

    @Test
    void consoleOffLeavesOnlyTheFile() throws Exception {
        bootstrap();
        Path file = dir.resolve("jircd.log");

        new LoggingSettingsApplier(context).apply(new Logging(false, file));
        log("file only");

        assertThat(rootAppenders()).singleElement().isInstanceOf(RollingFileAppender.class);
        assertThat(Files.readString(file)).contains("file only");
    }

    @Test
    void consoleComesBackWhenTurnedOnAgain() {
        bootstrap();
        LoggingSettingsApplier applier = new LoggingSettingsApplier(context);
        Path file = dir.resolve("jircd.log");

        applier.apply(new Logging(false, file));
        applier.apply(new Logging(true, file));

        assertThat(rootAppenders())
                .hasSize(2)
                .hasAtLeastOneElementOfType(ConsoleAppender.class)
                .hasAtLeastOneElementOfType(RollingFileAppender.class);
    }

    @Test
    void anUnchangedFileKeepsItsAppender() {
        bootstrap();
        LoggingSettingsApplier applier = new LoggingSettingsApplier(context);
        Path file = dir.resolve("jircd.log");
        applier.apply(new Logging(true, file));
        List<Appender<ILoggingEvent>> before = rootAppenders();

        applier.apply(new Logging(true, file));

        assertThat(rootAppenders()).containsExactlyElementsOf(before);
    }

    @Test
    void aNewPathTakesOverAndTheOldFileGetsNoMoreLines() throws Exception {
        bootstrap();
        LoggingSettingsApplier applier = new LoggingSettingsApplier(context);
        Path first = dir.resolve("first.log");
        Path second = dir.resolve("second.log");
        applier.apply(new Logging(true, first));
        log("before the switch");

        applier.apply(new Logging(true, second));
        log("after the switch");

        assertThat(Files.readString(first))
                .contains("before the switch")
                .doesNotContain("after the switch");
        assertThat(Files.readString(second))
                .contains("after the switch")
                .doesNotContain("before the switch");
        assertThat(rootAppenders()).filteredOn(RollingFileAppender.class::isInstance).hasSize(1);
    }

    @Test
    void removingTheFileStopsWritingIt() throws Exception {
        bootstrap();
        LoggingSettingsApplier applier = new LoggingSettingsApplier(context);
        Path file = dir.resolve("jircd.log");
        applier.apply(new Logging(true, file));

        applier.apply(Logging.DEFAULT);
        log("console only now");

        assertThat(rootAppenders()).singleElement().isInstanceOf(ConsoleAppender.class);
        assertThat(Files.readString(file)).doesNotContain("console only now");
    }

    @Test
    void aFileThatCannotBeOpenedKeepsThePreviousSetupAndWarns() throws Exception {
        bootstrap();
        LoggingSettingsApplier applier = new LoggingSettingsApplier(context);
        Path good = dir.resolve("good.log");
        applier.apply(new Logging(true, good));
        List<Appender<ILoggingEvent>> before = rootAppenders();
        ListAppender<ILoggingEvent> warnings = capture(LoggingSettingsApplier.class.getName());
        // Valid when the configuration was read; since then its directory was replaced by a file.
        Path gone = Files.writeString(dir.resolve("replaced"), "").resolve("jircd.log");

        applier.apply(new Logging(false, gone));
        log("still to the good file");

        assertThat(rootAppenders()).containsExactlyElementsOf(before);
        assertThat(Files.readString(good)).contains("still to the good file");
        assertThat(warnings.list)
                .anySatisfy(
                        e -> {
                            assertThat(e.getLevel()).isEqualTo(Level.WARN);
                            assertThat(e.getFormattedMessage()).contains(gone.toString());
                        });
    }

    @Test
    void underAnExternalConfigurationTheApplierChangesNothingAndSaysSoOnce() {
        // No bootstrap: the context was configured by someone else's logback file.
        ListAppender<ILoggingEvent> external = capture(org.slf4j.Logger.ROOT_LOGGER_NAME);
        LoggingSettingsApplier applier = new LoggingSettingsApplier(context);

        applier.apply(new Logging(false, dir.resolve("ignored.log")));
        applier.apply(Logging.DEFAULT);

        assertThat(rootAppenders()).containsExactly(external);
        assertThat(Files.exists(dir.resolve("ignored.log"))).isFalse();
        assertThat(external.list)
                .filteredOn(e -> e.getFormattedMessage().contains("logging section is ignored"))
                .singleElement()
                .satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.INFO));
    }

    private ListAppender<ILoggingEvent> capture(String loggerName) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        context.getLogger(loggerName).addAppender(appender);
        return appender;
    }
}
