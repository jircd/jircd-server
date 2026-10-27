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

class ExceptionModesTest {
    @Test
    void exceptionsAllowBannedUserIntoInviteOnlyChannelAndPermitMessages() throws Exception {
        Duration timeout = Duration.ofSeconds(5);
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome("alice", "alice");
            bob.registerAndAwaitWelcome("bob", "bob");
            assertThat(alice.readUntil("005", timeout))
                    .contains(
                            "EXCEPTS=e", "INVEX=I", "MAXLIST=Ibe:100", "CHANMODES=Ibe,k,l,imnpst");
            alice.send("JOIN #c");
            alice.readUntil("366", timeout);
            alice.send("MODE #c +ib *");
            alice.readUntil("MODE #c +ib", timeout);
            bob.send("JOIN #c");
            assertThat(bob.readUntil("474", timeout)).contains("474");
            alice.send("MODE #c +e bob");
            alice.readUntil("MODE #c +e", timeout);
            bob.send("JOIN #c");
            assertThat(bob.readUntil("473", timeout)).contains("473");
            alice.send("MODE #c +I bob");
            alice.readUntil("MODE #c +I", timeout);
            bob.send("MODE #c e");
            assertThat(bob.readUntil("348", timeout)).contains("bob!*@*");
            bob.readUntil("349", timeout);
            bob.send("MODE #c I");
            assertThat(bob.readUntil("346", timeout)).contains("bob!*@*");
            bob.readUntil("347", timeout);
            bob.send("JOIN #c");
            bob.readUntil("366", timeout);
            bob.send("PRIVMSG #c :allowed");
            assertThat(alice.readUntil("PRIVMSG #c", timeout)).contains("allowed");
            alice.send("MODE #c -e BOB");
            alice.readUntil("MODE #c -e", timeout);
            bob.readUntil("MODE #c -e", timeout);
            bob.send("PRIVMSG #c :blocked");
            assertThat(bob.readUntil("404", timeout)).contains("404");
        }
    }
}
