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

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;
import net.jircd.core.config.ConfigurationException;
import net.jircd.core.session.ConnectionHandler;

/** Test-only bridge to package-private application lifecycle seams. */
public final class JircdServerTestAccess {
    private JircdServerTestAccess() {}

    /** Constructs an application with a test-only rejection deadline. */
    public static JircdServerApplication createApplication(
            Path configPath, Consumer<Socket> acceptedSocketCustomizer, Duration rejectionDeadline)
            throws ConfigurationException, IOException {
        return new JircdServerApplication(configPath, acceptedSocketCustomizer, rejectionDeadline);
    }

    /** Returns the handler for controlled acceptance and drain lifecycle tests. */
    public static ConnectionHandler connectionHandler(JircdServerApplication application) {
        return application.connectionHandlerForTesting();
    }
}
