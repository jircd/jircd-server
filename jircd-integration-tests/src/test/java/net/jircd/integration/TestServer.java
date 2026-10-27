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

import com.password4j.Password;
import java.io.IOException;
import java.net.Socket;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;
import net.jircd.server.JircdServerApplication;
import net.jircd.server.JircdServerTestAccess;

/**
 * Starts a real {@link JircdServerApplication} on ephemeral ports for protocol-level integration
 * tests.
 */
public final class TestServer implements AutoCloseable {

    /** Enables all three Story 2 capabilities — none are enabled by default (research.md). */
    public static final String ALL_CAPABILITIES_ENABLED_YAML =
            """
      capabilities:
        message-tags: enabled
        server-time: enabled
        echo-message: enabled
      """;

    /** The address test listeners bind and test clients connect to. */
    public static final String LOOPBACK = "127.0.0.1";

    public static final String ADMIN_USERNAME = "root-admin";
    public static final String ADMIN_PASSWORD = "correct horse battery staple";

    /**
     * Enables {@code admin} (off by default, contracts/server-configuration.md) with one real,
     * bcrypt-hashed credential for {@link #ADMIN_USERNAME}/{@link #ADMIN_PASSWORD} — {@code admin}
     * itself MUST be enabled for {@code OPER} etc. to work at all.
     */
    public static String adminEnabledYaml() {
        return "server-extensions:\n"
                + "  admin: enabled\n"
                + "administratorCredentials:\n"
                + "  - username: "
                + ADMIN_USERNAME
                + "\n"
                + "    hashedPassword: \""
                + bcryptHash()
                + "\"\n";
    }

    /**
     * Same as {@link #adminEnabledYaml()}, but with an Argon2id-hashed credential instead of bcrypt
     * (008-argon2-admin-verification).
     */
    public static String argon2AdminEnabledYaml() {
        return "server-extensions:\n"
                + "  admin: enabled\n"
                + "administratorCredentials:\n"
                + "  - username: "
                + ADMIN_USERNAME
                + "\n"
                + "    hashedPassword: \""
                + argon2Hash()
                + "\"\n";
    }

    /** Enables {@code cloak} (off by default) alone, with no {@code admin}. */
    public static final String CLOAK_ENABLED_YAML = "server-extensions:\n  cloak: enabled\n";

    /**
     * Both {@code admin} and {@code cloak} enabled together, one {@code server-extensions:} block.
     */
    public static String adminAndCloakEnabledYaml() {
        return "server-extensions:\n"
                + "  admin: enabled\n"
                + "  cloak: enabled\n"
                + "administratorCredentials:\n"
                + "  - username: "
                + ADMIN_USERNAME
                + "\n"
                + "    hashedPassword: \""
                + bcryptHash()
                + "\"\n";
    }

    private static String bcryptHash() {
        return Password.hash(ADMIN_PASSWORD).withBcrypt().getResult();
    }

    private static String argon2Hash() {
        return Password.hash(ADMIN_PASSWORD).withArgon2().getResult();
    }

    public final JircdServerApplication application;
    public final Path configPath;

    private TestServer(JircdServerApplication application, Path configPath) {
        this.application = application;
        this.configPath = configPath;
    }

    public static TestServer start() throws Exception {
        return start("");
    }

    public static TestServer start(String extraYaml) throws Exception {
        return start(extraYaml, socket -> {});
    }

    /** Test seam for configuring accepted sockets before registration, without private access. */
    static TestServer start(String extraYaml, Consumer<Socket> acceptedSocketCustomizer)
            throws Exception {
        Path configPath = Files.createTempFile("jircd-test-", ".yaml");
        Files.writeString(configPath, baseYaml() + extraYaml);
        JircdServerApplication application =
                new JircdServerApplication(configPath, acceptedSocketCustomizer);
        application.start();
        return new TestServer(application, configPath);
    }

    /** Test-only rejection deadline override; ordinary starts exercise the production default. */
    static TestServer start(
            String extraYaml, Consumer<Socket> acceptedSocketCustomizer, Duration rejectionDeadline)
            throws Exception {
        Path configPath = Files.createTempFile("jircd-test-", ".yaml");
        Files.writeString(configPath, baseYaml() + extraYaml);
        JircdServerApplication application =
                JircdServerTestAccess.createApplication(
                        configPath, acceptedSocketCustomizer, rejectionDeadline);
        application.start();
        return new TestServer(application, configPath);
    }

    /**
     * The {@code serverName}/{@code listeners} preamble every config in this test suite starts
     * with. The TLS listener references a throwaway, long-lived fixture certificate ({@code
     * src/test/resources/tls/}) — since 004-fix-tls-certificate, a {@code tls: true} listener with
     * no certificate configured refuses to start, so every test relying on this base config needs a
     * real (if fake) one. Both listeners bind {@link #LOOPBACK}, not the wildcard address: an
     * OS-assigned wildcard port can be one another local process already listens on at {@code
     * 127.0.0.1}, and the tests' loopback clients would then reach that process instead (#38).
     */
    public static String baseYaml() {
        return "serverName: test.jircd.local\n"
                + "listeners:\n"
                + "  - port: 0\n"
                + "    bindAddress: "
                + LOOPBACK
                + "\n"
                + "    tls: false\n"
                + "  - port: 0\n"
                + "    bindAddress: "
                + LOOPBACK
                + "\n"
                + "    tls: true\n"
                + "    certPath: \""
                + resourcePath("tls/test-fullchain.pem")
                + "\"\n"
                + "    keyPath: \""
                + resourcePath("tls/test-privkey.pem")
                + "\"\n";
    }

    /**
     * Absolute filesystem path to a test resource, for config keys that expect a real file path.
     */
    public static String resourcePath(String classpathName) {
        var url = TestServer.class.getClassLoader().getResource(classpathName);
        if (url == null) {
            throw new IllegalStateException("Missing test resource: " + classpathName);
        }
        try {
            return Path.of(url.toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Malformed test resource URL: " + url, e);
        }
    }

    public int plaintextPort() {
        return application.plaintextPort();
    }

    public int tlsPort() {
        return application.tlsPort();
    }

    @Override
    public void close() throws IOException {
        application.stop();
    }
}
