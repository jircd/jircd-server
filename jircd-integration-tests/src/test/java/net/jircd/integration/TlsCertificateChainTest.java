/*
 * Copyright 2026 JIRCD Maintainers
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
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import net.jircd.core.config.ConfigurationException;
import net.jircd.core.config.TlsCertificateLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #167: a PEM {@code certPath} holding a chain (leaf first, then intermediates) presents the whole
 * chain, not only its first certificate.
 */
class TlsCertificateChainTest {

    @TempDir static Path dir;

    private static TestPki pki;
    private static Path keyFile;

    @BeforeAll
    static void generatePki() throws Exception {
        pki = TestPki.generate(dir);
        keyFile = Files.writeString(dir.resolve("leaf-key.pem"), pki.leafKeyPem);
    }

    private static String yaml(Path certFile) {
        return "serverName: test.jircd.local\n"
                + "listeners:\n"
                + "  - port: 0\n"
                + "    bindAddress: 127.0.0.1\n"
                + "    tls: true\n"
                + "    certPath: \""
                + certFile
                + "\"\n"
                + "    keyPath: \""
                + keyFile
                + "\"\n";
    }

    private static Path chainFile(String name, String... pems) throws Exception {
        return Files.writeString(dir.resolve(name), String.join("\n", pems));
    }

    @Test
    void theKeyEntryHoldsEveryCertificateOfTheFileInOrderLeafFirst() throws Exception {
        Path chain = chainFile("full.pem", pki.leafPem, pki.intermediatePem, pki.rootPem);
        KeyStore keyStore = TlsCertificateLoader.load(listener(chain));

        var loaded = keyStore.getCertificateChain("jircd");
        assertThat(loaded).hasSize(3);
        assertThat(((X509Certificate) loaded[0]).getSubjectX500Principal().getName())
                .isEqualTo("CN=localhost");
        assertThat(((X509Certificate) loaded[1]).getSubjectX500Principal().getName())
                .isEqualTo("CN=Test Intermediate");
        assertThat(((X509Certificate) loaded[2]).getSubjectX500Principal().getName())
                .isEqualTo("CN=Test Root");
    }

    @Test
    void theServerPresentsTheWholeChainOnTheWire() throws Exception {
        Path chain = chainFile("wire.pem", pki.leafPem, pki.intermediatePem);
        try (TestServer server = TestServer.start(yaml(chain));
                RawIrcClient client = RawIrcClient.connectTls(server.tlsPort())) {
            var presented = client.peerCertificateChain();
            assertThat(presented).hasSize(2);
            assertThat(((X509Certificate) presented[0]).getSubjectX500Principal().getName())
                    .isEqualTo("CN=localhost");
            assertThat(((X509Certificate) presented[1]).getSubjectX500Principal().getName())
                    .isEqualTo("CN=Test Intermediate");
        }
    }

    @Test
    void aSingleCertificateFileStillWorks() throws Exception {
        Path single = chainFile("single.pem", pki.leafPem);
        try (TestServer server = TestServer.start(yaml(single));
                RawIrcClient client = RawIrcClient.connectTls(server.tlsPort())) {
            assertThat(client.peerCertificateChain()).hasSize(1);
        }
    }

    @Test
    void aKeyThatMatchesOnlyALaterCertificateIsRejected() throws Exception {
        // CA first, leaf second: the key belongs to the second certificate, so serving the file
        // would present the CA as the server certificate with a key that can't sign for it.
        Path wrongOrder = chainFile("wrong-order.pem", pki.intermediatePem, pki.leafPem);
        assertThatThrownBy(() -> TestServer.start(yaml(wrongOrder)))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("does not match the first certificate");
    }

    @Test
    void anRsaKeyForAnEcLeafIsReportedAsAMismatchWithItsCause() throws Exception {
        var generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        byte[] der = generator.generateKeyPair().getPrivate().getEncoded();
        Path rsaKey = Files.writeString(dir.resolve("rsa-key.pem"), TestPki.pkcs8Pem(der));
        Path chain = chainFile("rsa-mismatch.pem", pki.leafPem, pki.intermediatePem);
        var listener =
                new net.jircd.core.config.ServerConfiguration.Listener(
                        0,
                        true,
                        java.net.InetAddress.getLoopbackAddress(),
                        chain.toString(),
                        rsaKey.toString(),
                        null,
                        null,
                        50);
        assertThatThrownBy(() -> TlsCertificateLoader.load(listener))
                .hasMessageContaining("does not match the first certificate");
    }

    @Test
    void aFileWithNoCertificateIsRejected() throws Exception {
        Path empty = chainFile("empty.pem", "not a certificate\n");
        assertThatThrownBy(() -> TestServer.start(yaml(empty)))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("invalid TLS certificate configuration")
                .hasMessageContaining("No certificate data found");
    }

    @Test
    void anEmptyCertificateFileIsRejected() throws Exception {
        Path empty = Files.writeString(dir.resolve("zero.pem"), "");
        assertThatThrownBy(() -> TestServer.start(yaml(empty)))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("contains no certificate");
    }

    private static Path pkcs12(String name, java.security.PrivateKey key, String... certPems)
            throws Exception {
        var factory = java.security.cert.CertificateFactory.getInstance("X.509");
        var chain = new java.security.cert.Certificate[certPems.length];
        for (int i = 0; i < certPems.length; i++) {
            chain[i] =
                    factory.generateCertificate(
                            new java.io.ByteArrayInputStream(
                                    certPems[i].getBytes(
                                            java.nio.charset.StandardCharsets.US_ASCII)));
        }
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        if (key != null) {
            // setKeyEntry does not check that the key belongs to the chain's first certificate.
            store.setKeyEntry("jircd", key, "changeit".toCharArray(), chain);
        } else {
            store.setCertificateEntry("jircd", chain[0]);
        }
        Path file = dir.resolve(name);
        try (var out = Files.newOutputStream(file)) {
            store.store(out, "changeit".toCharArray());
        }
        return file;
    }

    private static net.jircd.core.config.ServerConfiguration.Listener keystoreListener(Path file) {
        return new net.jircd.core.config.ServerConfiguration.Listener(
                0,
                true,
                java.net.InetAddress.getLoopbackAddress(),
                null,
                null,
                file.toString(),
                "changeit",
                50);
    }

    private static java.security.PrivateKey leafKey() throws Exception {
        String base64 =
                pki.leafKeyPem
                        .replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "")
                        .replaceAll("\\s", "");
        return java.security.KeyFactory.getInstance("EC")
                .generatePrivate(
                        new java.security.spec.PKCS8EncodedKeySpec(
                                java.util.Base64.getDecoder().decode(base64)));
    }

    @Test
    void aPkcs12KeyThatDoesNotMatchItsCertificateIsRejected() throws Exception {
        var other = java.security.KeyPairGenerator.getInstance("EC");
        other.initialize(256);
        Path file = pkcs12("mismatch.p12", other.generateKeyPair().getPrivate(), pki.leafPem);
        assertThatThrownBy(() -> TlsCertificateLoader.load(keystoreListener(file)))
                .hasMessageContaining("does not match the first certificate")
                .hasMessageContaining("mismatch.p12");
    }

    @Test
    void aValidPkcs12KeystoreStillLoads() throws Exception {
        Path file = pkcs12("valid.p12", leafKey(), pki.leafPem, pki.intermediatePem);
        KeyStore loaded = TlsCertificateLoader.load(keystoreListener(file));
        assertThat(loaded.getCertificateChain("jircd")).hasSize(2);
    }

    @Test
    void aPkcs12KeystoreWithNoKeyEntryIsRejected() throws Exception {
        Path file = pkcs12("no-key.p12", null, pki.leafPem);
        assertThatThrownBy(() -> TlsCertificateLoader.load(keystoreListener(file)))
                .hasMessageContaining("no private key entry")
                .hasMessageContaining("no-key.p12");
    }

    private static net.jircd.core.config.ServerConfiguration.Listener listener(Path certFile) {
        return new net.jircd.core.config.ServerConfiguration.Listener(
                0,
                true,
                java.net.InetAddress.getLoopbackAddress(),
                certFile.toString(),
                keyFile.toString(),
                null,
                null,
                50);
    }
}
