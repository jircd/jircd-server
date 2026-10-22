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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import org.slf4j.LoggerFactory;

/**
 * Captures a specific logger's output for assertions (009-connection-monitoring-log) — {@code
 * TestServer} runs the server in-process, so a {@link ListAppender} attached to the real logger
 * sees real output, with no need for a separate log-parsing/tailing mechanism.
 */
final class LogCapture implements AutoCloseable {

  private final List<Logger> loggers;
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  LogCapture(Class<?>... loggerClasses) {
    loggers =
        Arrays.stream(loggerClasses).map(type -> (Logger) LoggerFactory.getLogger(type)).toList();
    appender.start();
    loggers.forEach(logger -> logger.addAppender(appender));
  }

  List<String> messages() {
    return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  /** Polls for a captured message matching {@code predicate}, up to {@code timeout}. */
  String awaitMessage(Predicate<String> predicate, Duration timeout) throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      for (String message : messages()) {
        if (predicate.test(message)) {
          return message;
        }
      }
      Thread.sleep(50);
    }
    throw new AssertionError(
        "No captured log message matched within " + timeout + "; captured: " + messages());
  }

  @Override
  public void close() {
    loggers.forEach(logger -> logger.detachAppender(appender));
  }
}
