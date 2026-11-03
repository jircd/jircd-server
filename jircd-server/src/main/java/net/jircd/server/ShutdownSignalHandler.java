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
package net.jircd.server;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Signal; // NOSONAR S1191: the JDK has no public signal API

/**
 * Handles {@code TERM} ({@code docker stop}, systemd stop) and {@code INT} (Ctrl-C): the first one
 * runs the server's graceful shutdown, which tells every client why, and then exits with status 0,
 * where the JVM's default handling would cut clients off and exit 143 (spec 016 FR-009, research
 * R2). Later signals are ignored while that runs. Installed before the server is built, so a signal
 * during startup also exits 0; one that arrives before {@code main} runs gets the JVM's default.
 */
public final class ShutdownSignalHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ShutdownSignalHandler.class);

    private final AtomicBoolean signalled = new AtomicBoolean();
    private final AtomicReference<Runnable> shutdown = new AtomicReference<>(() -> {});
    private final IntConsumer exit;

    ShutdownSignalHandler(IntConsumer exit) {
        this.exit = exit;
    }

    /** Takes over {@code TERM} and {@code INT} for this process. */
    public static ShutdownSignalHandler install() {
        ShutdownSignalHandler handler = new ShutdownSignalHandler(System::exit);
        for (String name : List.of("TERM", "INT")) {
            Signal.handle(new Signal(name), signal -> handler.onSignal(signal.getName()));
        }
        return handler;
    }

    /** From now on a signal shuts {@code application} down before exiting. */
    public void attach(JircdServerApplication application) {
        attach(() -> application.shutdown(JircdServerApplication.SHUTDOWN_REASON));
    }

    void attach(Runnable shutdownAction) {
        shutdown.set(shutdownAction);
    }

    void onSignal(String name) {
        if (!signalled.compareAndSet(false, true)) {
            return;
        }
        LOG.info("Received SIG{}; shutting down", name);
        try {
            shutdown.get().run();
        } catch (RuntimeException e) {
            LOG.warn("Graceful shutdown failed; exiting anyway", e);
        } finally {
            exit.accept(0);
        }
    }
}
