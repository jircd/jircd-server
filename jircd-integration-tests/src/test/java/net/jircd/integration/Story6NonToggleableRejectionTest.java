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
 * {@code moderation} and {@code capability-negotiation} are core mechanisms (FR-035/FR-036), never
 * registered as toggleable extensions — {@code ExtensionRegistry#find} simply doesn't know either
 * id, so {@code EXTENSION} rejects them the same way it rejects any unknown id.
 */
class Story6NonToggleableRejectionTest {

    @Test
    void neverToggleableCoreMechanismsAreRejectedAsUnknown() throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient root = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            root.registerAndAwaitWelcome("root", "root");
            root.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            root.readUntil("381", Duration.ofSeconds(5));

            root.send("EXTENSION DISABLE moderation");
            assertThat(root.readUntil("421", Duration.ofSeconds(5))).contains("421");

            root.send("EXTENSION DISABLE capability-negotiation");
            assertThat(root.readUntil("421", Duration.ofSeconds(5))).contains("421");
        }
    }
}
