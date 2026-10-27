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
package net.jircd.serverextensions.admin;

import com.password4j.Password;
import java.util.List;
import net.jircd.core.config.ServerConfiguration;

/**
 * Verifies {@code OPER} credentials against {@code ServerConfiguration.administratorCredentials}
 * (FR-034), the same salted, computationally-expensive hashing approach FR-024's account
 * credentials use (research.md "Administrator credential storage") — never plain text. Supports
 * bcrypt (`$2a$`/`$2b$`/`$2y$`) and Argon2id (`$argon2id$`) hashes, both verified via Password4j
 * (008-argon2-admin-verification research.md "Password hashing library choice").
 */
public final class AdminCredentialVerifier {

    private AdminCredentialVerifier() {}

    public static boolean verify(
            List<ServerConfiguration.AdministratorCredential> credentials,
            String username,
            String password) {
        for (ServerConfiguration.AdministratorCredential credential : credentials) {
            if (credential.username().equals(username)) {
                return verifyHash(password, credential.hashedPassword());
            }
        }
        return false;
    }

    private static boolean verifyHash(String password, String hashedPassword) {
        try {
            if (hashedPassword.startsWith("$2a$")
                    || hashedPassword.startsWith("$2b$")
                    || hashedPassword.startsWith("$2y$")) {
                return Password.check(password, hashedPassword).withBcrypt();
            }
            if (hashedPassword.startsWith("$argon2id$")) {
                return Password.check(password, hashedPassword).withArgon2();
            }
            return false;
        } catch (IllegalArgumentException malformedOrUnsupportedHash) {
            return false;
        }
    }
}
