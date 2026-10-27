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

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 010-wallops-notices: administrator broadcast via {@code WALLOPS} to {@code +w}-opted-in users.
 */
class WallopsCommandTest {

    @Test
    void adminWallopsIsDeliveredToOptedInRecipientWithSenderHostmaskPrefix() throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient admin = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            admin.registerAndAwaitWelcome("admin", "admin");
            admin.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            admin.readUntil("381", Duration.ofSeconds(5));

            bob.registerAndAwaitWelcome("bob", "bob");
            bob.send("MODE bob +w");
            bob.readUntil("MODE bob +w", Duration.ofSeconds(5));

            admin.send("WALLOPS :server restarting in 5 minutes");

            String received = bob.readUntil("WALLOPS", Duration.ofSeconds(5));
            assertThat(received).contains("admin").contains("server restarting in 5 minutes");
        }
    }

    @Test
    void wallopsWithNoOptedInRecipientsCompletesSilently() throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient admin = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            admin.registerAndAwaitWelcome("admin", "admin");
            admin.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            admin.readUntil("381", Duration.ofSeconds(5));
            bob.registerAndAwaitWelcome("bob", "bob"); // connected, but never sets +w

            admin.send("WALLOPS :test with no subscribers");

            assertThat(bob.readLinesFor(Duration.ofSeconds(1)))
                    .noneMatch(line -> line.contains("WALLOPS"));

            admin.send("PING still-fine");
            assertThat(admin.readUntil("PONG", Duration.ofSeconds(5))).contains("PONG");
        }
    }

    @Test
    void adminReceivesOwnWallopsOnlyWhenOptedIn() throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient admin = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            admin.registerAndAwaitWelcome("admin", "admin");
            admin.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            admin.readUntil("381", Duration.ofSeconds(5));

            admin.send("MODE admin +w");
            admin.readUntil("MODE admin +w", Duration.ofSeconds(5));

            admin.send("WALLOPS :self-test");
            assertThat(admin.readUntil("WALLOPS", Duration.ofSeconds(5))).contains("self-test");
        }
    }

    @Test
    void wallopsWithMissingOrEmptyTextIsRejectedWithNoDelivery() throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient admin = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            admin.registerAndAwaitWelcome("admin", "admin");
            admin.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            admin.readUntil("381", Duration.ofSeconds(5));

            bob.registerAndAwaitWelcome("bob", "bob");
            bob.send("MODE bob +w");
            bob.readUntil("MODE bob +w", Duration.ofSeconds(5));

            admin.send("WALLOPS");
            assertThat(admin.readUntil("461", Duration.ofSeconds(5))).contains("461");

            admin.send("WALLOPS :");
            assertThat(admin.readUntil("412", Duration.ofSeconds(5))).contains("412");

            assertThat(bob.readLinesFor(Duration.ofSeconds(1)))
                    .noneMatch(line -> line.contains("WALLOPS"));
        }
    }

    @Test
    void userSelfControlsWallopsModeAndCannotChangeAnotherUsers() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient carol = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient dave = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            carol.registerAndAwaitWelcome("carol", "carol");
            dave.registerAndAwaitWelcome("dave", "dave");

            carol.send("MODE carol +w");
            carol.readUntil("MODE carol +w", Duration.ofSeconds(5));
            carol.send("MODE carol");
            assertThat(carol.readUntil("221", Duration.ofSeconds(5))).contains("221").contains("w");

            carol.send("MODE carol -w");
            carol.readUntil("MODE carol -w", Duration.ofSeconds(5));
            carol.send("MODE carol");
            assertThat(carol.readUntil("221", Duration.ofSeconds(5))).doesNotContain("w");

            carol.send("MODE dave +w");
            assertThat(carol.readUntil("502", Duration.ofSeconds(5))).contains("502");
            dave.send("MODE dave");
            assertThat(dave.readUntil("221", Duration.ofSeconds(5))).doesNotContain("w");
        }
    }

    @Test
    void nonAdministratorWallopsIsRejectedAndDeliveredToNoOne() throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient eve = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            eve.registerAndAwaitWelcome("eve", "eve"); // never OPERs
            bob.registerAndAwaitWelcome("bob", "bob");
            bob.send("MODE bob +w");
            bob.readUntil("MODE bob +w", Duration.ofSeconds(5));

            eve.send("WALLOPS :should not be delivered");
            assertThat(eve.readUntil("481", Duration.ofSeconds(5))).contains("481");

            assertThat(bob.readLinesFor(Duration.ofSeconds(1)))
                    .noneMatch(line -> line.contains("WALLOPS"));
        }
    }

    @Test
    void wallopsIsRejectedAfterAdministratorPrivilegeIsSelfRevoked() throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient admin = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            admin.registerAndAwaitWelcome("admin", "admin");
            admin.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            admin.readUntil("381", Duration.ofSeconds(5));

            admin.send("MODE admin -o");
            admin.readUntil("MODE admin -o", Duration.ofSeconds(5));

            admin.send("WALLOPS :should now be rejected");
            assertThat(admin.readUntil("481", Duration.ofSeconds(5))).contains("481");
        }
    }
}
