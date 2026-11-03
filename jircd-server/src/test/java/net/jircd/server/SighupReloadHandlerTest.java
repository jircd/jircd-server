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

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** A rejected SIGHUP reload says which file was rejected (spec 016 FR-010). */
class SighupReloadHandlerTest {

    @TempDir Path dir;

    @Test
    void aReloadFailureNamesTheConfigurationFile() throws Exception {
        Path config =
                Files.writeString(
                        dir.resolve("jircd.yaml"),
                        """
                        serverName: test.jircd.local
                        listeners:
                          - port: 0
                            bindAddress: 127.0.0.1
                            tls: false
                        """);
        JircdServerApplication application = new JircdServerApplication(config);
        Files.writeString(config, "serverName: [unclosed\n");

        Logger log = (Logger) LoggerFactory.getLogger(SighupReloadHandler.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        log.addAppender(events);
        try {
            SighupReloadHandler.reload(application.reloader());
        } finally {
            log.detachAppender(events);
        }

        assertThat(events.list)
                .anySatisfy(
                        e -> {
                            assertThat(e.getLevel()).isEqualTo(Level.WARN);
                            assertThat(e.getFormattedMessage())
                                    .startsWith("SIGHUP reload failed")
                                    .contains("invalid YAML")
                                    .contains(config.toString());
                        });
    }
}
