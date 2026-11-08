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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The directory a test mounts read-only at {@code /etc/jircd}: {@code jircd.yaml} plus the test
 * certificate and key. The container runs as uid 65532, not as the host user that wrote these
 * files, so the directory is world-traversable and the files world-readable; {@code @TempDir}
 * directories are created {@code 700}.
 */
final class ContainerFixture {

    /** Inside the container. */
    static final String CONFIG_PATH = "/etc/jircd/jircd.yaml";

    static final String SERVER_NAME = "test.jircd.local";

    /** The password of the {@code tls/test-keystore.p12} test resource. */
    static final String KEYSTORE_PASSWORD = "changeit";

    private ContainerFixture() {}

    /** Plaintext 6667 and TLS 6697 on every address, then {@code extraYaml}. */
    static String baseYaml(String extraYaml) {
        return "serverName: "
                + SERVER_NAME
                + "\n"
                + "listeners:\n"
                + "  - port: 6667\n"
                + "    tls: false\n"
                + "  - port: 6697\n"
                + "    tls: true\n"
                + "    certPath: /etc/jircd/cert.pem\n"
                + "    keyPath: /etc/jircd/key.pem\n"
                + extraYaml;
    }

    /** As {@link #baseYaml} but the TLS listener reads a PKCS12 keystore instead of PEM files. */
    static String pkcs12Yaml(String extraYaml) {
        return "serverName: "
                + SERVER_NAME
                + "\n"
                + "listeners:\n"
                + "  - port: 6667\n"
                + "    tls: false\n"
                + "  - port: 6697\n"
                + "    tls: true\n"
                + "    keystorePath: /etc/jircd/keystore.p12\n"
                + "    keystorePassword: \""
                + KEYSTORE_PASSWORD
                + "\"\n"
                + extraYaml;
    }

    /** {@code jircd.yaml} ({@link #baseYaml}{@code (extraYaml)}), the certificate and the key. */
    static Map<String, byte[]> files(String extraYaml) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("jircd.yaml", baseYaml(extraYaml).getBytes(StandardCharsets.UTF_8));
        files.put("cert.pem", resource("tls/test-fullchain.pem"));
        files.put("key.pem", resource("tls/test-privkey.pem"));
        files.put("keystore.p12", resource("tls/test-keystore.p12"));
        return files;
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = ContainerFixture.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("Missing test resource: " + name);
            return in.readAllBytes();
        }
    }

    /**
     * Writes {@link #baseYaml}{@code (extraYaml)}, the certificate and the key into {@code dir}.
     */
    static Path write(Path dir, String extraYaml) throws IOException {
        return writeRaw(dir, baseYaml(extraYaml));
    }

    /** Writes {@code yaml} verbatim as {@code jircd.yaml}, plus the certificate and the key. */
    static Path writeRaw(Path dir, String yaml) throws IOException {
        Files.createDirectories(dir);
        copyResource("tls/test-fullchain.pem", dir.resolve("cert.pem"));
        copyResource("tls/test-privkey.pem", dir.resolve("key.pem"));
        copyResource("tls/test-keystore.p12", dir.resolve("keystore.p12"));
        Path config = dir.resolve("jircd.yaml");
        Files.writeString(config, yaml);
        openUp(dir);
        return config;
    }

    /** A host directory the container user may write to, for a mounted log volume. */
    static Path writableDirectory(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"));
        return dir;
    }

    private static void copyResource(String name, Path target) throws IOException {
        try (InputStream in = ContainerFixture.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("Missing test resource: " + name);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void openUp(Path dir) throws IOException {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) {
                if (Files.isRegularFile(file)) {
                    Files.setPosixFilePermissions(
                            file, PosixFilePermissions.fromString("rw-r--r--"));
                }
            }
        }
    }
}
