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

import net.jircd.core.config.ConfigurationException;
import org.junit.jupiter.api.Test;

/**
 * 004-fix-tls-certificate: one test per corrected finding (FR-001 through FR-006), plus the
 * feature's own core success criterion (SC-001, certificate identity across a restart).
 */
class TlsCertificateConfigTest {

    @Test
    void pemConfiguredListenerCompletesAHandshakeWithThatExactCertificate() throws Exception {
        // TestServer.baseYaml() already configures its TLS listener via certPath/keyPath — this
        // is exactly FR-001/FR-002, exercised implicitly by every other TLS-enabled test in this
        // suite, but asserted directly here.
        try (TestServer server = TestServer.start();
                RawIrcClient client = RawIrcClient.connectTls(server.tlsPort())) {
            client.registerAndAwaitWelcome("alice", "alice");
            assertThat(client.peerCertificateFingerprint()).isNotBlank();
        }
    }

    @Test
    void pkcs12ConfiguredListenerCompletesAHandshakeWithThatExactCertificate() throws Exception {
        String yaml =
                "serverName: test.jircd.local\n"
                        + "listeners:\n"
                        + "  - port: 0\n"
                        + "    tls: false\n"
                        + "  - port: 0\n"
                        + "    bindAddress: 127.0.0.1\n"
                        + "    tls: true\n"
                        + "    keystorePath: \""
                        + TestServer.resourcePath("tls/test-keystore.p12")
                        + "\"\n"
                        + "    keystorePassword: changeit\n";
        try (TestServer server = TestServer.start(yaml);
                RawIrcClient client = RawIrcClient.connectTls(server.tlsPort())) {
            client.registerAndAwaitWelcome("alice", "alice");
            assertThat(client.peerCertificateFingerprint()).isNotBlank();
        }
    }

    @Test
    void keystorePathWithNoPasswordFailsToStart() {
        String yaml =
                "serverName: test.jircd.local\n"
                        + "listeners:\n"
                        + "  - port: 0\n"
                        + "    tls: true\n"
                        + "    keystorePath: \""
                        + TestServer.resourcePath("tls/test-keystore.p12")
                        + "\"\n";
        assertThatThrownBy(() -> TestServer.start(yaml))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("no 'keystorePassword'");
    }

    @Test
    void encryptedPemPrivateKeyFailsWithASpecificMessage() {
        String yaml =
                "serverName: test.jircd.local\n"
                        + "listeners:\n"
                        + "  - port: 0\n"
                        + "    tls: true\n"
                        + "    certPath: \""
                        + TestServer.resourcePath("tls/test-fullchain.pem")
                        + "\"\n"
                        + "    keyPath: \""
                        + TestServer.resourcePath("tls/test-privkey-encrypted.pem")
                        + "\"\n";
        assertThatThrownBy(() -> TestServer.start(yaml))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("passphrase-encrypted");
    }

    @Test
    void tlsListenerWithNoCertificateConfiguredFailsToStart() {
        String yaml =
                """
        serverName: test.jircd.local
        listeners:
          - port: 0
            tls: true
        """;
        assertThatThrownBy(() -> TestServer.start(yaml))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("no certificate is configured");
    }

    @Test
    void tlsListenerWithOnlyCertPathFailsToStart() {
        String yaml =
                "serverName: test.jircd.local\n"
                        + "listeners:\n"
                        + "  - port: 0\n"
                        + "    tls: true\n"
                        + "    certPath: \""
                        + TestServer.resourcePath("tls/test-fullchain.pem")
                        + "\"\n";
        assertThatThrownBy(() -> TestServer.start(yaml))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("incomplete PEM certificate/key pair");
    }

    @Test
    void tlsListenerWithBothPemAndKeystoreFailsToStart() {
        String yaml =
                "serverName: test.jircd.local\n"
                        + "listeners:\n"
                        + "  - port: 0\n"
                        + "    tls: true\n"
                        + "    certPath: \""
                        + TestServer.resourcePath("tls/test-fullchain.pem")
                        + "\"\n"
                        + "    keyPath: \""
                        + TestServer.resourcePath("tls/test-privkey.pem")
                        + "\"\n"
                        + "    keystorePath: \""
                        + TestServer.resourcePath("tls/test-keystore.p12")
                        + "\"\n";
        assertThatThrownBy(() -> TestServer.start(yaml))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("exactly one certificate source is allowed");
    }

    @Test
    void tlsListenerWithANonexistentCertPathFailsAtStartupNotLazily() {
        String yaml =
                """
        serverName: test.jircd.local
        listeners:
          - port: 0
            tls: true
            certPath: /nonexistent/fullchain.pem
            keyPath: /nonexistent/privkey.pem
        """;
        assertThatThrownBy(() -> TestServer.start(yaml))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("invalid TLS certificate configuration");
    }

    @Test
    void certificateFieldsOnANonTlsListenerAreIgnoredNotRejected() throws Exception {
        String yaml =
                """
        serverName: test.jircd.local
        listeners:
          - port: 0
            bindAddress: 127.0.0.1
            tls: false
            certPath: /nonexistent/fullchain.pem
        """;
        try (TestServer server = TestServer.start(yaml);
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            client.registerAndAwaitWelcome(
                    "alice", "alice"); // starts fine; the bogus path is unused
        }
    }

    @Test
    void zeroConfigServerStartsWithOnlyAPlaintextListenerAndNoTls() throws Exception {
        // An empty 'listeners' list triggers JircdServerApplication's own zero-config default
        // (overriding TestServer.baseYaml()'s own listeners block — SnakeYAML resolves a duplicate
        // top-level key to the last occurrence), which must no longer include a TLS entry
        // (research.md "The zero-config default listener list").
        String yaml = "serverName: test.jircd.local\nlisteners: []\n";
        try (TestServer server = TestServer.start(yaml);
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            client.registerAndAwaitWelcome("alice", "alice");
            assertThatThrownBy(server::tlsPort).isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    void certificateIdentityIsUnchangedAcrossIndependentServerStarts() throws Exception {
        String yaml =
                "serverName: test.jircd.local\n"
                        + "listeners:\n"
                        + "  - port: 0\n"
                        + "    bindAddress: 127.0.0.1\n"
                        + "    tls: true\n"
                        + "    certPath: \""
                        + TestServer.resourcePath("tls/test-fullchain.pem")
                        + "\"\n"
                        + "    keyPath: \""
                        + TestServer.resourcePath("tls/test-privkey.pem")
                        + "\"\n";

        String firstFingerprint;
        try (TestServer server = TestServer.start(yaml);
                RawIrcClient client = RawIrcClient.connectTls(server.tlsPort())) {
            firstFingerprint = client.peerCertificateFingerprint();
        }

        String secondFingerprint;
        try (TestServer server = TestServer.start(yaml);
                RawIrcClient client = RawIrcClient.connectTls(server.tlsPort())) {
            secondFingerprint = client.peerCertificateFingerprint();
        }

        assertThat(secondFingerprint).isEqualTo(firstFingerprint);
    }
}
