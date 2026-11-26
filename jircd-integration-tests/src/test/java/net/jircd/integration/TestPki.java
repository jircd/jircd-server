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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A throwaway three-level PKI (root, intermediate, leaf) generated at test run time with the JDK's
 * {@code keytool}, so no key material is committed (#167). Everything lives under the directory the
 * caller supplies.
 */
final class TestPki {

    private static final String STORE_PASSWORD = "changeit";
    private static final long KEYTOOL_TIMEOUT_SECONDS = 60;

    final String rootPem;
    final String intermediatePem;
    final String leafPem;
    final String leafKeyPem;

    private TestPki(String rootPem, String intermediatePem, String leafPem, String leafKeyPem) {
        this.rootPem = rootPem;
        this.intermediatePem = intermediatePem;
        this.leafPem = leafPem;
        this.leafKeyPem = leafKeyPem;
    }

    static TestPki generate(Path dir) throws Exception {
        Path store = dir.resolve("pki.p12");
        genKeyPair(store, "root", "CN=Test Root", "bc:c");
        genKeyPair(store, "inter", "CN=Test Intermediate", "bc:c");
        genKeyPair(store, "leaf", "CN=localhost", "bc=ca:false");

        Path rootCert = dir.resolve("root.pem");
        keytool(
                "-exportcert",
                "-rfc",
                "-alias",
                "root",
                "-keystore",
                store.toString(),
                "-storepass",
                STORE_PASSWORD,
                "-file",
                rootCert.toString());
        Path interCert = sign(dir, store, "inter", "root", "bc:c");
        Path leafCert = sign(dir, store, "leaf", "inter", "san=dns:localhost");

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            keyStore.load(in, STORE_PASSWORD.toCharArray());
        }
        byte[] der = keyStore.getKey("leaf", STORE_PASSWORD.toCharArray()).getEncoded();
        String keyPem =
                "-----BEGIN PRIVATE KEY-----\n"
                        + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                                .encodeToString(der)
                        + "\n-----END PRIVATE KEY-----\n";
        return new TestPki(
                Files.readString(rootCert),
                Files.readString(interCert),
                Files.readString(leafCert),
                keyPem);
    }

    private static void genKeyPair(Path store, String alias, String dn, String ext)
            throws IOException {
        keytool(
                "-genkeypair",
                "-alias",
                alias,
                "-keyalg",
                "EC",
                "-groupname",
                "secp256r1",
                "-dname",
                dn,
                "-ext",
                ext,
                "-validity",
                "3",
                "-keystore",
                store.toString(),
                "-storetype",
                "PKCS12",
                "-storepass",
                STORE_PASSWORD);
    }

    private static Path sign(Path dir, Path store, String alias, String issuer, String ext)
            throws IOException {
        Path csr = dir.resolve(alias + ".csr");
        Path cert = dir.resolve(alias + ".pem");
        keytool(
                "-certreq",
                "-alias",
                alias,
                "-keystore",
                store.toString(),
                "-storepass",
                STORE_PASSWORD,
                "-file",
                csr.toString());
        keytool(
                "-gencert",
                "-rfc",
                "-alias",
                issuer,
                "-ext",
                ext,
                "-validity",
                "3",
                "-keystore",
                store.toString(),
                "-storepass",
                STORE_PASSWORD,
                "-infile",
                csr.toString(),
                "-outfile",
                cert.toString());
        return cert;
    }

    private static void keytool(String... args) throws IOException {
        // The keytool ToolProvider is not resolved in classpath mode, so run the JDK's executable.
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));
        // Output goes to a file, so a chatty or hung keytool can never block on a full pipe.
        Path outputFile = Files.createTempFile("keytool-", ".log");
        try {
            Process process =
                    new ProcessBuilder(command)
                            .redirectErrorStream(true)
                            .redirectOutput(outputFile.toFile())
                            .start();
            boolean finished;
            try {
                finished = process.waitFor(KEYTOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("interrupted waiting for keytool", e);
            }
            if (!finished) {
                process.destroyForcibly();
            }
            String output = Files.readString(outputFile, StandardCharsets.UTF_8);
            if (!finished) {
                throw new IOException(
                        "keytool did not finish within "
                                + KEYTOOL_TIMEOUT_SECONDS
                                + "s: "
                                + output);
            }
            if (process.exitValue() != 0) {
                throw new IOException("keytool failed: " + output);
            }
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }
}
