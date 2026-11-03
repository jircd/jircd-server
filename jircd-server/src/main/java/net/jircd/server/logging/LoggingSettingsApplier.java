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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import java.nio.file.Path;
import java.util.Objects;
import net.jircd.core.config.ServerConfiguration;
import org.slf4j.LoggerFactory;

/**
 * Applies the {@code logging} settings (spec 016 FR-006, FR-006b) to logback's root logger, at
 * startup and after each reload. Only a destination whose setting changed is touched: an unchanged
 * console or file keeps its appender and its lines. A new file appender is opened and attached
 * before the old one is detached; if it cannot be opened, the previous destinations stay and a WARN
 * says why.
 *
 * <p>The switch is not atomic, and an event logged at the instant of a switch can be lost or reach
 * only one destination. A thread already dispatching an event works from the appender list it read
 * before the change: the event skips an appender attached after that read, and if the old appender
 * is stopped before the event reaches it, the stopped appender discards it. Events logged while
 * both file appenders are attached go to both files.
 *
 * <p>The applier owns the root appenders only when {@link BootstrapLogbackConfigurator} set logback
 * up. When logback was configured from {@code -Dlogback.configurationFile}, it changes nothing and
 * logs once that the {@code logging} section is ignored.
 *
 * <p>The current state is read back from the root logger, not kept here, so any number of appliers
 * on one context agree; changes are serialized on the context.
 */
public final class LoggingSettingsApplier {

    /** The line layout for every destination; it is fixed (spec 016 Assumptions). */
    public static final String PATTERN =
            "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n";

    public static final FileSize MAX_FILE_SIZE = FileSize.valueOf("50MB");
    public static final FileSize TOTAL_SIZE_CAP = FileSize.valueOf("1GB");
    public static final int MAX_HISTORY_DAYS = 30;

    static final String CONSOLE = "CONSOLE";
    static final String FILE = "FILE";

    /** Set on a context by {@link BootstrapLogbackConfigurator}: jircd owns its root appenders. */
    static final String MANAGED = "net.jircd.logging.managed";

    private static final String EXTERNAL_NOTICE_SENT = "net.jircd.logging.externalNoticeSent";

    private final LoggerContext context;
    private final Logger log;

    public LoggingSettingsApplier(LoggerContext context) {
        this.context = context;
        this.log = context.getLogger(LoggingSettingsApplier.class);
    }

    /** The applier for the logging of this process, or {@code null} when logback is not in use. */
    public static LoggingSettingsApplier forThisProcess() {
        return LoggerFactory.getILoggerFactory() instanceof LoggerContext context
                ? new LoggingSettingsApplier(context)
                : null;
    }

    /** Makes the root appenders match {@code settings}; see the class description. */
    public void apply(ServerConfiguration.Logging settings) {
        synchronized (context) {
            if (context.getObject(MANAGED) == null) {
                noteExternalConfigurationOnce();
                return;
            }
            Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            Appender<ILoggingEvent> console = root.getAppender(CONSOLE);
            Appender<ILoggingEvent> file = root.getAppender(FILE);
            Path currentFile =
                    file instanceof RollingFileAppender<ILoggingEvent> rolling
                            ? Path.of(rolling.getFile())
                            : null;
            boolean fileChanged = !Objects.equals(currentFile, settings.file());

            RollingFileAppender<ILoggingEvent> newFile = null;
            if (fileChanged && settings.file() != null) {
                newFile = startFileAppender(context, settings.file());
                if (newFile == null) {
                    log.warn(
                            "Cannot open log file {}; keeping the previous logging destinations",
                            settings.file());
                    return;
                }
            }
            // Attach before detaching, so an event dispatched after the attach finds a destination.
            // One already being dispatched works from the old list and can be lost (class doc).
            if (newFile != null) root.addAppender(newFile);
            if (settings.console() && console == null) root.addAppender(consoleAppender(context));
            if (fileChanged && file != null) stop(root, file);
            if (!settings.console() && console != null) stop(root, console);
        }
    }

    /** The standard-output appender with the fixed pattern, started. */
    static ConsoleAppender<ILoggingEvent> consoleAppender(LoggerContext context) {
        ConsoleAppender<ILoggingEvent> console = new ConsoleAppender<>();
        console.setContext(context);
        console.setName(CONSOLE);
        console.setEncoder(encoder(context));
        console.start();
        return console;
    }

    private static RollingFileAppender<ILoggingEvent> startFileAppender(
            LoggerContext context, Path path) {
        RollingFileAppender<ILoggingEvent> appender = new RollingFileAppender<>();
        appender.setContext(context);
        appender.setName(FILE);
        appender.setFile(path.toString());
        appender.setEncoder(encoder(context));

        SizeAndTimeBasedRollingPolicy<ILoggingEvent> policy = new SizeAndTimeBasedRollingPolicy<>();
        policy.setContext(context);
        policy.setParent(appender);
        policy.setFileNamePattern(path + ".%d{yyyy-MM-dd}.%i.gz");
        policy.setMaxFileSize(MAX_FILE_SIZE);
        policy.setTotalSizeCap(TOTAL_SIZE_CAP);
        policy.setMaxHistory(MAX_HISTORY_DAYS);
        policy.start();
        appender.setRollingPolicy(policy);
        appender.start();
        if (!appender.isStarted()) {
            appender.stop();
            return null;
        }
        return appender;
    }

    private static PatternLayoutEncoder encoder(LoggerContext context) {
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(PATTERN);
        encoder.start();
        return encoder;
    }

    private static void stop(Logger root, Appender<ILoggingEvent> appender) {
        root.detachAppender(appender);
        appender.stop();
    }

    private void noteExternalConfigurationOnce() {
        if (context.getObject(EXTERNAL_NOTICE_SENT) != null) return;
        context.putObject(EXTERNAL_NOTICE_SENT, Boolean.TRUE);
        String source = System.getProperty("logback.configurationFile");
        log.info(
                "Logging is configured by {}; the logging section is ignored",
                source == null ? "an external logback configuration" : source);
    }
}
