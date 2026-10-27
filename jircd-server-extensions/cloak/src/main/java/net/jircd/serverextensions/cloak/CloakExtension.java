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
package net.jircd.serverextensions.cloak;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.jircd.core.extension.HostnameDisplayExtension;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.ServerExtension;

/**
 * Replaces a client's real hostname/IP with an obfuscated value shown to other clients (FR-031),
 * claiming the {@code hostname-display} extension point (research.md "Cloak extension boundary") —
 * {@code jircd-core} always keeps the real value on {@code ClientSession} itself and only asks this
 * extension, when enabled, for the *display* value; administrators and this client's own
 * self-lookups bypass it entirely, reading the real value directly.
 */
public final class CloakExtension implements ServerExtension, HostnameDisplayExtension {

    public static final String ID = "cloak";
    private static final String HASH_ALGORITHM = "SHA-256";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String extensionPoint() {
        return "hostname-display";
    }

    @Override
    public void start(ServerContext context) {}

    @Override
    public void stop() {}

    /** A stable, deterministic, non-reversible-in-practice value — no algorithm is mandated. */
    @Override
    public String display(String realHostname) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hash = digest.digest(realHostname.getBytes(StandardCharsets.UTF_8));
            return "user-" + HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(HASH_ALGORITHM + " is always available on the JDK", e);
        }
    }
}
