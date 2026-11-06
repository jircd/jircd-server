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
package net.jircd.integration.container;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A container that cannot start exits 1 with one {@code Refusing to start:} line naming the problem
 * (spec 016 FR-010, contracts/container-contract.md "Startup error messages").
 */
@Tag("container")
class ContainerFailureTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @TempDir Path dir;

    @Test
    void noConfigurationMounted() throws Exception {
        JircdContainer.Exited exited = JircdContainer.runToExit(null, TIMEOUT);
        assertThat(exited.exitCode()).isEqualTo(1);
        assertThat(exited.logs())
                .contains(
                        "Refusing to start: Configuration file not found: "
                                + ContainerFixture.CONFIG_PATH)
                .doesNotContain("(configuration file:");
    }

    @Test
    void invalidYaml() throws Exception {
        ContainerFixture.writeRaw(dir.resolve("etc"), "serverName: [unclosed\n");
        JircdContainer.Exited exited = JircdContainer.runToExit(dir.resolve("etc"), TIMEOUT);
        assertThat(exited.exitCode()).isEqualTo(1);
        assertThat(exited.logs())
                .contains("Refusing to start: root contains invalid YAML")
                .contains("(configuration file: " + ContainerFixture.CONFIG_PATH + ")");
    }

    @Test
    void aConfigurationTheContainerUserCannotRead() throws Exception {
        // Root-owned, mode 600: uid 65532 cannot read it.
        JircdContainer.Exited exited =
                JircdContainer.runToExitWithRootOwnedConfig(
                        ContainerFixture.baseYaml(""), 0600, TIMEOUT);
        assertThat(exited.exitCode()).isEqualTo(1);
        assertThat(exited.logs())
                .contains(
                        "Refusing to start: Configuration file not readable by this user: "
                                + ContainerFixture.CONFIG_PATH);
    }

    @Test
    void aTlsCertificatePathThatIsNotMounted() throws Exception {
        ContainerFixture.writeRaw(
                dir.resolve("etc"),
                """
                listeners:
                  - port: 6697
                    tls: true
                    certPath: /etc/jircd/missing.pem
                    keyPath: /etc/jircd/key.pem
                """);
        JircdContainer.Exited exited = JircdContainer.runToExit(dir.resolve("etc"), TIMEOUT);
        assertThat(exited.exitCode()).isEqualTo(1);
        assertThat(exited.logs()).contains("Refusing to start:").contains("/etc/jircd/missing.pem");
    }

    @Test
    void aLoggingSectionWithNoDestination() throws Exception {
        ContainerFixture.write(dir.resolve("etc"), "logging:\n  console: false\n");
        JircdContainer.Exited exited = JircdContainer.runToExit(dir.resolve("etc"), TIMEOUT);
        assertThat(exited.exitCode()).isEqualTo(1);
        assertThat(exited.logs())
                .contains(
                        "Refusing to start: logging: console is off and no file is set; the"
                                + " server would produce no log output (configuration file: "
                                + ContainerFixture.CONFIG_PATH
                                + ")");
    }
}
