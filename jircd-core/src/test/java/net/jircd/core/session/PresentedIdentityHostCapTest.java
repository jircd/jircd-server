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

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import net.jircd.core.config.ServerConfiguration;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.HostnameDisplayExtension;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.ServerExtension;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class PresentedIdentityHostCapTest {

    private static final int CAP = ServerConfiguration.PRESENTED_HOST_MAX_BYTES;

    private static final class Cloak implements ServerExtension, HostnameDisplayExtension {
        private final String shown;

        Cloak(String shown) {
            this.shown = shown;
        }

        @Override
        public String id() {
            return "cap-cloak";
        }

        @Override
        public String extensionPoint() {
            return "hostname-display";
        }

        @Override
        public void start(ServerContext context) {
            // Stateless test extension.
        }

        @Override
        public void stop() {
            // Stateless test extension.
        }

        @Override
        public String display(String hostname) {
            return shown;
        }
    }

    private static String displayed(String shown) {
        ExtensionRegistry extensions = new ExtensionRegistry();
        extensions.register(new Cloak(shown));
        extensions.enable("cap-cloak");
        ClientSession session =
                new ClientSession("c1", "203.0.113.9", RateLimitBucket.withDefaults());
        return PresentedIdentity.displayHostname(session, extensions);
    }

    @Test
    void aHostAtTheCapPassesThroughUnchanged() {
        String host = "h".repeat(CAP);
        assertThat(displayed(host)).isEqualTo(host);
    }

    @Test
    void anOverlongHostIsTruncatedNotReplacedByTheRealAddress() {
        String host = "h".repeat(300);
        assertThat(displayed(host)).isEqualTo("h".repeat(CAP)).doesNotContain("203.0.113.9");
    }

    @Test
    void truncationKeepsWholeCodePoints() {
        String shown = displayed("界".repeat(40));
        assertThat(shown).isEqualTo("界".repeat(CAP / 3));
        assertThat(shown.getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(CAP);
    }

    @Test
    void repeatedOverlongHostsWarnOncePerThrottleWindow() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        PresentedIdentity.useClockForTest(now::get);
        Logger logger = (Logger) LoggerFactory.getLogger(PresentedIdentity.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (int i = 0; i < 5; i++) {
                assertThat(displayed("x".repeat(500))).hasSize(CAP);
            }
            assertThat(appender.list).as("five calls in one window").hasSize(1);
            assertThat(appender.list.getFirst().getFormattedMessage())
                    .contains("cap-cloak")
                    .contains("63 bytes");

            now.addAndGet(59_000_000_000L);
            displayed("x".repeat(500));
            assertThat(appender.list).as("still inside the minute").hasSize(1);

            now.addAndGet(2_000_000_000L);
            displayed("x".repeat(500));
            displayed("x".repeat(500));
            assertThat(appender.list).as("one more after the window").hasSize(2);
        } finally {
            logger.detachAppender(appender);
            PresentedIdentity.useClockForTest(System::nanoTime);
        }
    }

    @Test
    void aHugeHostCostsOnlyTheCapAndKeepsTheLeadingCodePoints() {
        String huge = "😀".repeat(5_000_000);
        assertThat(displayed(huge)).isEqualTo("😀".repeat(CAP / 4));
    }
}
