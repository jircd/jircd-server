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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Loads a {@link ServerConfiguration.Listener}'s explicitly configured certificate — a PEM
 * certificate/chain and private key pair, or a PKCS12 keystore — into a {@link KeyStore}
 * (004-fix-tls-certificate FR-001, FR-002, FR-005). Lives in {@code config}, not {@code session},
 * so both {@link ConfigurationLoader} (eager startup validation, FR-006) and {@code
 * net.jircd.core.session.TlsSupport} (actual {@code SSLContext} construction) can depend on it
 * without introducing a {@code config}→{@code session} package cycle.
 */
public final class TlsCertificateLoader {

    private static final String PEM_KEY_HEADER = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_KEY_FOOTER = "-----END PRIVATE KEY-----";
    private static final String ENCRYPTED_PEM_KEY_HEADER = "-----BEGIN ENCRYPTED PRIVATE KEY-----";
    private static final String LEGACY_PEM_KEY_HEADER = "-----BEGIN RSA PRIVATE KEY-----";

    /** The password used for the in-memory {@link KeyStore} entry the PEM form builds. */
    public static final char[] PEM_KEY_PASSWORD = new char[0];

    private TlsCertificateLoader() {}

    /**
     * @return {@code listener.keystorePassword()} as a char array. No default is substituted —
     *     {@code "changeit"}-style well-known defaults are exactly the kind of credential that
     *     defeats a PKCS12 keystore's own password-based encryption of its private key entry;
     *     {@link ConfigurationLoader} already guarantees a {@code keystorePath} listener has a
     *     non-null password before this is ever called.
     */
    public static char[] keystorePassword(ServerConfiguration.Listener listener) {
        return listener.keystorePassword().toCharArray();
    }

    /**
     * Loads {@code listener}'s configured certificate — {@link
     * ServerConfiguration.Listener#keystorePath()} if present, otherwise the PEM pair — into a
     * {@link KeyStore}. Callers already know exactly one form is present (validated by {@link
     * ConfigurationLoader#load}) before this is ever called.
     */
    public static KeyStore load(ServerConfiguration.Listener listener)
            throws GeneralSecurityException, IOException {
        return listener.keystorePath() != null
                ? loadPkcs12Keystore(listener.keystorePath(), keystorePassword(listener))
                : buildPemKeyStore(listener.certPath(), listener.keyPath());
    }

    private static KeyStore loadPkcs12Keystore(String path, char[] password)
            throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            keyStore.load(in, password);
        }
        return keyStore;
    }

    /**
     * Parses the certificate and private key directly from their PEM files and assembles an
     * in-memory PKCS12 {@link KeyStore} — no temp file is ever written.
     */
    private static KeyStore buildPemKeyStore(String certPath, String keyPath)
            throws GeneralSecurityException, IOException {
        Certificate certificate;
        try (InputStream in = Files.newInputStream(Path.of(certPath))) {
            certificate = CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        PrivateKey privateKey = readPemPrivateKey(Path.of(keyPath));

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry(
                "jircd", privateKey, PEM_KEY_PASSWORD, new Certificate[] {certificate});
        return keyStore;
    }

    /**
     * Reads a PKCS#8-encoded, unencrypted private key (the format Let's Encrypt/certbot's own
     * {@code privkey.pem} uses by default, research.md "PEM parsing") — a passphrase-encrypted key
     * or the legacy PKCS#1 format are both out of scope, but rejected with a specific, actionable
     * message rather than a generic parse failure.
     */
    private static PrivateKey readPemPrivateKey(Path path)
            throws GeneralSecurityException, IOException {
        String pem = Files.readString(path);
        if (pem.contains(ENCRYPTED_PEM_KEY_HEADER)) {
            throw new GeneralSecurityException(
                    "PEM private key at "
                            + path
                            + " is passphrase-encrypted, which jircd does not support — decrypt it first,"
                            + " e.g. 'openssl pkcs8 -in <key> -topk8 -nocrypt -out <output>'");
        }
        if (pem.contains(LEGACY_PEM_KEY_HEADER)) {
            throw new GeneralSecurityException(
                    "PEM private key at "
                            + path
                            + " is in the legacy PKCS#1 format, which jircd does not support — convert it"
                            + " first, e.g. 'openssl pkcs8 -in <key> -topk8 -nocrypt -out <output>'");
        }
        if (!pem.contains(PEM_KEY_HEADER)) {
            throw new GeneralSecurityException(
                    "PEM private key at " + path + " is not in a recognized PKCS#8 PEM format");
        }

        String base64 =
                pem.replace(PEM_KEY_HEADER, "").replace(PEM_KEY_FOOTER, "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(der);
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(keySpec);
        } catch (InvalidKeySpecException e) {
            return KeyFactory.getInstance("EC").generatePrivate(keySpec);
        }
    }
}
