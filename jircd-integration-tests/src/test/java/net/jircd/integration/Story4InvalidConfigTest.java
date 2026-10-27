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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import net.jircd.core.config.ConfigurationException;
import net.jircd.core.extension.Extension;
import net.jircd.server.JircdServerApplication;
import net.jircd.server.SighupReloadHandler;
import org.junit.jupiter.api.Test;
import sun.misc.Signal;

class Story4InvalidConfigTest {

    @Test
    void unknownCapabilityIdRejectedAtStartup() throws Exception {
        Path configPath = Files.createTempFile("jircd-test-", ".yaml");
        Files.writeString(
                configPath,
                TestServer.baseYaml()
                        + """
            capabilities:
              nonexistent: enabled
            """);

        assertThatThrownBy(() -> new JircdServerApplication(configPath))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("nonexistent")
                .hasMessageContaining("capabilities");
    }

    @Test
    void sectionKindMismatchRejectedAtStartup() throws Exception {
        Path configPath = Files.createTempFile("jircd-test-", ".yaml");
        // cloak is a ServerExtension id — belongs under server-extensions, not capabilities.
        Files.writeString(
                configPath,
                TestServer.baseYaml()
                        + """
            capabilities:
              cloak: enabled
            """);

        assertThatThrownBy(() -> new JircdServerApplication(configPath))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("cloak");
    }

    @Test
    void invalidSighupReloadLeavesServerRunningOnPreviousConfig() throws Exception {
        try (TestServer server = TestServer.start(TestServer.ALL_CAPABILITIES_ENABLED_YAML)) {
            SighupReloadHandler.install(server.application);

            Files.writeString(
                    server.configPath,
                    TestServer.baseYaml()
                            + """
              capabilities:
                moderation: enabled
              """);
            Signal.raise(new Signal("HUP"));

            // A failed reload logs and leaves prior state untouched — settle briefly, then confirm
            // the previously-valid configuration (all three capabilities enabled) is still in
            // effect.
            Thread.sleep(300);
            assertThat(server.application.extensionRegistry().stateOf("message-tags"))
                    .isEqualTo(Extension.State.ENABLED);
            assertThat(server.application.extensionRegistry().stateOf("server-time"))
                    .isEqualTo(Extension.State.ENABLED);
            assertThat(server.application.extensionRegistry().stateOf("echo-message"))
                    .isEqualTo(Extension.State.ENABLED);

            try (RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
                client.send("CAP LS 302");
                String capList = client.readUntil("CAP * LS", java.time.Duration.ofSeconds(5));
                assertThat(capList).contains("message-tags");
            }
        }
    }
}
