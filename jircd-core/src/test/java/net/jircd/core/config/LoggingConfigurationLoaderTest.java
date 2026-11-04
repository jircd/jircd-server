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
package net.jircd.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The {@code logging} section (spec 016 FR-006, FR-006a; contracts/logging-config.md). */
class LoggingConfigurationLoaderTest {

    @TempDir Path dir;

    private static ConfigurationLoader loader() {
        return new ConfigurationLoader(Set.of(), Set.of());
    }

    private static ServerConfiguration load(String yaml) throws Exception {
        return loader().load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String quoted(Path path) {
        return "\"" + path + "\"";
    }

    private static boolean runningAsRoot() {
        return "root".equals(System.getProperty("user.name"));
    }

    @Test
    void absentSectionMeansConsoleOnlyAndNoFile() throws Exception {
        ServerConfiguration.Logging logging = load("").logging();
        assertThat(logging.console()).isTrue();
        assertThat(logging.file()).isNull();
        assertThat(logging).isEqualTo(ServerConfiguration.Logging.DEFAULT);
    }

    @Test
    void emptySectionUsesTheDefaults() throws Exception {
        assertThat(load("logging: {}").logging()).isEqualTo(ServerConfiguration.Logging.DEFAULT);
    }

    @Test
    void fileWithConsoleDefaultedOn() throws Exception {
        Path file = dir.resolve("jircd.log");
        ServerConfiguration.Logging logging = load("logging:\n  file: " + quoted(file)).logging();
        assertThat(logging.console()).isTrue();
        assertThat(logging.file()).isEqualTo(file.toAbsolutePath().normalize());
    }

    @Test
    void fileOnlyWithConsoleOff() throws Exception {
        Path file = dir.resolve("jircd.log");
        ServerConfiguration.Logging logging =
                load("logging:\n  console: false\n  file: " + quoted(file)).logging();
        assertThat(logging.console()).isFalse();
        assertThat(logging.file()).isEqualTo(file.toAbsolutePath().normalize());
    }

    @Test
    void anExistingWritableFileIsAccepted() throws Exception {
        Path file = Files.writeString(dir.resolve("jircd.log"), "earlier lines\n");
        assertThat(load("logging:\n  file: " + quoted(file)).logging().file())
                .isEqualTo(file.toAbsolutePath().normalize());
    }

    @Test
    void aRelativeFileResolvesAgainstTheWorkingDirectory() throws Exception {
        // The working directory of the test JVM exists and is writable (Gradle's module dir).
        ServerConfiguration.Logging logging = load("logging:\n  file: jircd-test.log").logging();
        assertThat(logging.file())
                .isEqualTo(Path.of("jircd-test.log").toAbsolutePath().normalize())
                .isAbsolute();
    }

    @Test
    void consoleOffWithoutAFileIsRejected() {
        assertThatThrownBy(() -> load("logging:\n  console: false"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage(
                        "logging: console is off and no file is set; the server would produce no"
                                + " log output");
    }

    @ParameterizedTest
    @ValueSource(strings = {"'true'", "1", "yes please", "[]", "{}", "null"})
    void consoleMustBeABoolean(String value) {
        assertThatThrownBy(() -> load("logging:\n  console: " + value))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("logging.console");
    }

    @ParameterizedTest
    @ValueSource(strings = {"''", "'   '", "42", "[]", "{}"})
    void fileMustBeANonBlankString(String value) {
        assertThatThrownBy(() -> load("logging:\n  file: " + value))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("logging.file");
    }

    @Test
    void theSectionMustBeAMapping() {
        assertThatThrownBy(() -> load("logging: stdout"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("logging");
    }

    @Test
    void unknownKeysAreRejectedSoATypoCannotSilentlyDisableTheFile() {
        assertThatThrownBy(() -> load("logging:\n  fiel: /var/log/jircd.log"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("logging.fiel");
    }

    @ParameterizedTest
    @ValueSource(strings = {"console", "file"})
    void aNestedCredentialIsNeverEchoedInAnError(String key) {
        String yaml =
                "logging:\n  "
                        + key
                        + ":\n    hashedPassword: s3cr3t-value\n    keystorePassword: other-s3cr3t\n";
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("logging." + key)
                .hasMessageNotContaining("s3cr3t");
    }

    @Test
    void aComplexKeyIsRejectedWithoutEchoingACredentialInside() {
        String yaml = "logging:\n  ? {hashedPassword: s3cr3t-value}\n  : true\n";
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageStartingWith("logging")
                .hasMessageNotContaining("s3cr3t");
    }

    @Test
    void aNullKeyIsAConfigurationErrorNotACrash() {
        assertThatThrownBy(() -> load("logging:\n  ~: true\n"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageStartingWith("logging");
    }

    @Test
    void underAnExternalLogbackConfigurationTheSectionIsNotChecked() throws Exception {
        String previous = System.setProperty("logback.configurationFile", "/etc/custom.xml");
        try {
            Path missing = dir.resolve("absent-dir").resolve("jircd.log");
            // Each of these is rejected without the property; with it, logback's own file rules.
            assertThat(load("logging:\n  file: " + quoted(missing)).logging())
                    .isEqualTo(ServerConfiguration.Logging.DEFAULT);
            assertThat(load("logging:\n  console: false").logging())
                    .isEqualTo(ServerConfiguration.Logging.DEFAULT);
        } finally {
            if (previous == null) {
                System.clearProperty("logback.configurationFile");
            } else {
                System.setProperty("logback.configurationFile", previous);
            }
        }
    }

    @Test
    void aMissingDirectoryIsRejectedWithThePath() {
        Path missing = dir.resolve("nope");
        String yaml = "logging:\n  file: " + quoted(missing.resolve("j.log"));
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("logging.file directory does not exist: " + missing);
    }

    @Test
    void aNonWritableDirectoryIsRejectedWithThePath() throws Exception {
        assumeFalse(runningAsRoot(), "root can write to any directory");
        Path readOnly = Files.createDirectory(dir.resolve("ro"));
        Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            String yaml = "logging:\n  file: " + quoted(readOnly.resolve("j.log"));
            assertThatThrownBy(() -> load(yaml))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessage("logging.file directory is not writable: " + readOnly);
        } finally {
            Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void aDirectoryInPlaceOfTheFileIsRejected() throws Exception {
        Path directory = Files.createDirectory(dir.resolve("jircd.log"));
        String yaml = "logging:\n  file: " + quoted(directory);
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("logging.file is not a regular file: " + directory);
    }

    @Test
    void anExistingReadOnlyFileIsRejected() throws Exception {
        assumeFalse(runningAsRoot(), "root can write to any file");
        Path file = Files.writeString(dir.resolve("jircd.log"), "");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
        String yaml = "logging:\n  file: " + quoted(file);
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("logging.file is not writable: " + file);
    }

    @Test
    void aMissingConfigurationFileIsNamed() {
        Path missing = dir.resolve("jircd.yaml");
        assertThatThrownBy(() -> loader().load(missing))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("Configuration file not found: " + missing);
    }

    @Test
    void anUnreadableConfigurationFileIsNamed() throws Exception {
        assumeFalse(runningAsRoot(), "root can read any file");
        Path file = Files.writeString(dir.resolve("jircd.yaml"), "");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("-w-------"));
        assertThatThrownBy(() -> loader().load(file))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("Configuration file not readable by this user: " + file);
    }

    @Test
    void aReadableConfigurationFileLoads() throws Exception {
        Path file = Files.writeString(dir.resolve("jircd.yaml"), "serverName: a.example\n");
        assertThat(loader().load(file).serverName()).isEqualTo("a.example");
    }

    @Test
    void anExplicitlyNullSectionIsRejectedLikeAnyNonMapping() {
        assertThatThrownBy(() -> load("logging:\n"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("logging must be a mapping");
        assertThatThrownBy(() -> load("logging: ~"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("logging must be a mapping");
    }

    @Test
    void aFilesystemRootCannotBeTheLogFile() {
        Path root = dir.getRoot();
        String yaml = "logging:\n  file: " + quoted(root);
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("logging.file")
                .hasMessageContaining(root.toString())
                .hasMessageNotContaining("null");
    }

    @Test
    void theConfigurationFileItselfCannotBeTheLogFile() throws Exception {
        Path config = Files.writeString(dir.resolve("jircd.yaml"), "");
        Files.writeString(config, "logging:\n  file: " + quoted(config) + "\n");

        assertThatThrownBy(() -> loader().load(config))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("logging.file must not be the configuration file");
    }

    @Test
    void aSymlinkToTheConfigurationFileCannotBeTheLogFile() throws Exception {
        Path config = Files.writeString(dir.resolve("jircd.yaml"), "");
        Path link = Files.createSymbolicLink(dir.resolve("log-link"), config);
        Files.writeString(config, "logging:\n  file: " + quoted(link) + "\n");

        assertThatThrownBy(() -> loader().load(config))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("logging.file must not be the configuration file");
    }

    @Test
    void anUnreadableConfigurationPathIsReportedWithItsPath() throws Exception {
        // A directory opens but cannot be read: an IOException other than missing or denied.
        Path directory = Files.createDirectory(dir.resolve("a-directory"));

        assertThatThrownBy(() -> loader().load(directory))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("Could not read configuration file: " + directory);
    }

    @Test
    void aPercentSignInTheLogPathIsRejectedBecauseLogbackReadsItAsAToken() {
        Path file = dir.resolve("100%d.log");
        String yaml = "logging:\n  file: " + quoted(file);
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("logging.file must not contain '%'");
    }
}
