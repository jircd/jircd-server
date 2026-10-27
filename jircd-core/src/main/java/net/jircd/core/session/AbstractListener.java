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
package net.jircd.core.session;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared accept-thread lifecycle; accepted connections transfer to the connection handler. */
abstract class AbstractListener<S extends Closeable> implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractListener.class);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(3);

    protected final S serverSocket;
    private final Consumer<Socket> connectionHandler;
    private final String threadName;
    private final Duration closeTimeout;
    private volatile boolean running = true;
    private final AtomicReference<Thread> listenerThread = new AtomicReference<>();

    AbstractListener(S serverSocket, Consumer<Socket> connectionHandler, String threadName) {
        this(serverSocket, connectionHandler, threadName, CLOSE_TIMEOUT);
    }

    AbstractListener(
            S serverSocket,
            Consumer<Socket> connectionHandler,
            String threadName,
            Duration closeTimeout) {
        this.serverSocket = serverSocket;
        this.connectionHandler = connectionHandler;
        this.threadName = threadName;
        this.closeTimeout = closeTimeout;
    }

    /** Starts the virtual thread that accepts incoming connections. */
    public final void start() {
        listenerThread.set(Thread.ofVirtual().name(threadName).start(this::acceptLoop));
    }

    /** Returns the actual bound port, including an automatically assigned ephemeral port. */
    public abstract int boundPort();

    protected abstract Socket accept() throws IOException;

    private void acceptLoop() {
        while (running) {
            try {
                // ConnectionHandler owns and closes each accepted connection.
                connectionHandler.accept(accept());
            } catch (IOException e) {
                if (running) {
                    LOG.warn("{} accept loop error", threadName, e);
                }
            }
        }
    }

    /** Closes the socket and waits up to three seconds; throws if the accept thread cannot exit. */
    @Override
    public final void close() throws IOException {
        close(closeTimeout);
    }

    /** Closes the listener and waits within the caller's remaining shutdown budget. */
    public final void close(Duration timeout) throws IOException {
        running = false;
        Exception failure = null;
        try {
            serverSocket.close();
        } catch (IOException | RuntimeException closeFailure) {
            failure = closeFailure;
        } finally {
            try {
                joinAcceptThread(timeout);
            } catch (IOException | RuntimeException joinFailure) {
                if (failure == null) {
                    failure = joinFailure;
                } else if (failure != joinFailure) {
                    failure.addSuppressed(joinFailure);
                }
            }
        }
        if (failure instanceof IOException ioFailure) {
            throw ioFailure;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
    }

    private void joinAcceptThread(Duration timeout) throws IOException {
        Thread thread = listenerThread.get();
        if (thread != null) {
            try {
                if (!thread.join(timeout)) {
                    throw new IOException("Timed out stopping " + threadName + " after " + timeout);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while stopping " + threadName, e);
            }
        }
    }
}
