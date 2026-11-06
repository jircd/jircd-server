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
package net.jircd.core.testing;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

/**
 * Captures a specific logger's output for assertions (009-connection-monitoring-log) — {@code
 * TestServer} runs the server in-process, so an appender attached to the real logger sees real
 * output, with no need for a separate log-parsing/tailing mechanism.
 *
 * <p>Server threads append while the test thread reads, so the captured lines live behind a lock
 * (logback's {@code ListAppender} exposes a plain {@code ArrayList}, which races: issue #124).
 * {@link #messages()} returns a copy taken under the lock, and {@link #awaitMessage} waits on a
 * condition that every new event signals, instead of polling.
 */
public final class LogCapture implements AutoCloseable {

    private final List<Logger> loggers;
    private final CapturingAppender appender = new CapturingAppender();

    public LogCapture(Class<?>... loggerClasses) {
        loggers =
                Arrays.stream(loggerClasses)
                        .map(type -> (Logger) LoggerFactory.getLogger(type))
                        .toList();
        appender.start();
        loggers.forEach(logger -> logger.addAppender(appender));
    }

    /** A consistent snapshot of the lines captured so far, in arrival order. */
    public List<String> messages() {
        return appender.snapshot();
    }

    /**
     * One captured log event, reduced to what tests assert on.
     *
     * @param level the severity, as an SLF4J level
     * @param message the formatted message
     * @param throwableMessage the message of the attached throwable, or {@code null} if none
     */
    public record CapturedEvent(Level level, String message, String throwableMessage) {}

    /** A consistent snapshot of the captured events, in arrival order. */
    public List<CapturedEvent> events() {
        return appender.events();
    }

    /** Waits for a captured message matching {@code predicate}, up to {@code timeout}. */
    public String awaitMessage(Predicate<String> predicate, Duration timeout)
            throws InterruptedException {
        return appender.await(predicate, timeout);
    }

    @Override
    public void close() {
        loggers.forEach(logger -> logger.detachAppender(appender));
        appender.stop();
    }

    private static final class CapturingAppender extends AppenderBase<ILoggingEvent> {

        private final ReentrantLock lock = new ReentrantLock();
        private final Condition arrived = lock.newCondition();
        private final List<CapturedEvent> lines = new ArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            // Converted outside the lock: logback events are owned by the logging thread.
            CapturedEvent captured =
                    new CapturedEvent(
                            toSlf4j(event.getLevel()),
                            event.getFormattedMessage(),
                            event.getThrowableProxy() == null
                                    ? null
                                    : event.getThrowableProxy().getMessage());
            lock.lock();
            try {
                lines.add(captured);
                arrived.signalAll();
            } finally {
                lock.unlock();
            }
        }

        /** Logback emits only these five levels; the ranges also place any custom int level. */
        private static Level toSlf4j(ch.qos.logback.classic.Level level) {
            int value = level.toInt();
            if (value >= ch.qos.logback.classic.Level.ERROR_INT) return Level.ERROR;
            if (value >= ch.qos.logback.classic.Level.WARN_INT) return Level.WARN;
            if (value >= ch.qos.logback.classic.Level.INFO_INT) return Level.INFO;
            if (value >= ch.qos.logback.classic.Level.DEBUG_INT) return Level.DEBUG;
            return Level.TRACE;
        }

        List<String> snapshot() {
            lock.lock();
            try {
                return lines.stream().map(CapturedEvent::message).toList();
            } finally {
                lock.unlock();
            }
        }

        List<CapturedEvent> events() {
            lock.lock();
            try {
                return List.copyOf(lines);
            } finally {
                lock.unlock();
            }
        }

        String await(Predicate<String> predicate, Duration timeout) throws InterruptedException {
            long remainingNanos = timeout.toNanos();
            int scanned = 0;
            lock.lock();
            try {
                while (true) {
                    for (; scanned < lines.size(); scanned++) {
                        String message = lines.get(scanned).message();
                        if (predicate.test(message)) {
                            return message;
                        }
                    }
                    if (remainingNanos <= 0) {
                        throw new AssertionError(
                                "No captured log message matched within "
                                        + timeout
                                        + "; captured: "
                                        + lines.stream().map(CapturedEvent::message).toList());
                    }
                    remainingNanos = arrived.awaitNanos(remainingNanos);
                }
            } finally {
                lock.unlock();
            }
        }
    }
}
