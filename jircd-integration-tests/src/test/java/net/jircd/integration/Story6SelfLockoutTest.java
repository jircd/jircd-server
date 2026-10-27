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

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Disabling {@code admin} in-band never unregisters its command handlers (there's no such
 * mechanism) — instead every admin-gated handler except {@code OPER} live-checks that {@code admin}
 * is still enabled (contracts/irc-protocol-commands.md "Self-lockout"), so an already-{@code
 * OPER}'d session immediately loses access to every other admin command, including re-enabling
 * {@code admin} itself.
 */
class Story6SelfLockoutTest {

    @Test
    void disablingAdminLocksOutAlreadyOperedSessionFromEveryOtherAdminCommand() throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient root = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            root.registerAndAwaitWelcome("root", "root");
            root.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            root.readUntil("381", Duration.ofSeconds(5));

            root.send("EXTENSION DISABLE admin");
            assertThat(root.readUntil("NOTICE", Duration.ofSeconds(5)))
                    .contains("admin is now disabled");

            root.send("WHOHOST root");
            assertThat(root.readUntil("481", Duration.ofSeconds(5))).contains("481");

            root.send("EXTENSION ENABLE admin");
            assertThat(root.readUntil("481", Duration.ofSeconds(5))).contains("481");
        }
    }
}
