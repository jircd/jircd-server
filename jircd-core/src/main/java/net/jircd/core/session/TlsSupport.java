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

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import net.jircd.core.config.ServerConfiguration;
import net.jircd.core.config.TlsCertificateLoader;

/**
 * Builds the {@link SSLContext} the optional TLS listener uses (FR-018), from a listener's
 * explicitly configured certificate — either a PEM certificate/chain and private key pair, or a
 * PKCS12 keystore (004-fix-tls-certificate FR-001, FR-002, FR-005), loaded via {@link
 * TlsCertificateLoader}. {@link net.jircd.core.config.ConfigurationLoader} already guarantees,
 * before this is ever called, that exactly one of the two forms is present — this never falls back
 * to generating a certificate of its own.
 */
public final class TlsSupport {

    private TlsSupport() {}

    public static SSLContext buildServerContext(ServerConfiguration.Listener listener)
            throws GeneralSecurityException, IOException {
        KeyStore keyStore = TlsCertificateLoader.load(listener);
        char[] keyPassword =
                listener.keystorePath() != null
                        ? TlsCertificateLoader.keystorePassword(listener)
                        : TlsCertificateLoader.PEM_KEY_PASSWORD;

        KeyManagerFactory keyManagerFactory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, keyPassword);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagerFactory.getKeyManagers(), null, null);
        return context;
    }
}
