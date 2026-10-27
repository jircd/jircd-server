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
 * 007-bare-mode-query: a bare {@code MODE #channel} query returns the channel's current mode
 * settings ({@code 324 RPL_CHANNELMODEIS}) and creation time ({@code 329 RPL_CHANNELCREATED})
 * instead of the generic unknown-mode error.
 */
class ChannelModeIsTest {

    @Test
    void modeStringIncludesActiveBooleanAndValueCarryingFlagsWithTheirValues() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient chanop = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            chanop.registerAndAwaitWelcome("chanop", "chanop");
            chanop.send("JOIN #chan");
            chanop.readUntil("353", Duration.ofSeconds(5));

            chanop.send("MODE #chan +int");
            chanop.readUntil("MODE #chan", Duration.ofSeconds(5));
            chanop.send("MODE #chan +l 5");
            chanop.readUntil("MODE #chan +l", Duration.ofSeconds(5));
            chanop.send("MODE #chan +k secret");
            chanop.readUntil("MODE #chan +k", Duration.ofSeconds(5));

            chanop.send("MODE #chan");
            String modeIs = chanop.readUntil("324", Duration.ofSeconds(5));
            // CoreChannelModes.ALL's iteration order isn't guaranteed stable across JVM runs
            // (backed by Set.of(), whose own iteration order is deliberately randomized per launch)
            // — so the letters and values may appear in any order, just like irctest's own
            // testChannelModeIs only checks flag membership as a set, not a sequence. Parse the 324
            // line's own params instead of relying on either.
            String[] fields = modeIs.substring(modeIs.indexOf("#chan")).split(" ");
            String letters = fields[1];
            assertThat(letters).startsWith("+");
            java.util.Set<Character> letterSet = new java.util.HashSet<>();
            for (char c : letters.toCharArray()) {
                letterSet.add(c);
            }
            assertThat(letterSet).contains('i', 'n', 't', 'l', 'k');
            assertThat(java.util.List.of(fields).subList(2, fields.length)).contains("5", "secret");

            assertThat(chanop.readUntil("329", Duration.ofSeconds(5))).contains("329");
        }
    }

    @Test
    void modeStringIsBarePlusWhenNothingIsActive() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient chanop = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            chanop.registerAndAwaitWelcome("chanop", "chanop");
            chanop.send("JOIN #chan");
            chanop.readUntil("353", Duration.ofSeconds(5));

            chanop.send("MODE #chan");
            String modeIs = chanop.readUntil("324", Duration.ofSeconds(5));
            // No colon needed on the wire — "+" alone has no space, isn't empty, doesn't start with
            // ":".
            assertThat(modeIs).endsWith("#chan +");
        }
    }

    @Test
    void banListDoesNotAppearInTheModeIsReply() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient chanop = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            chanop.registerAndAwaitWelcome("chanop", "chanop");
            chanop.send("JOIN #chan");
            chanop.readUntil("353", Duration.ofSeconds(5));
            chanop.send("MODE #chan +b mask!*@*");
            chanop.readUntil("MODE #chan +b", Duration.ofSeconds(5));

            chanop.send("MODE #chan");
            String modeIs = chanop.readUntil("324", Duration.ofSeconds(5));
            assertThat(modeIs).doesNotContain("b");
        }
    }

    @Test
    void nonOperatorMemberCanQuerySuccessfully() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient chanop = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            chanop.registerAndAwaitWelcome("chanop", "chanop");
            chanop.send("JOIN #chan");
            chanop.readUntil("353", Duration.ofSeconds(5));
            bob.registerAndAwaitWelcome("bob", "bob");
            bob.send("JOIN #chan");
            bob.readUntil("353", Duration.ofSeconds(5));

            bob.send("MODE #chan");
            assertThat(bob.readUntil("324", Duration.ofSeconds(5))).contains("324");
        }
    }

    @Test
    void nonMemberOfASecretChannelGetsNoSuchChannel() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient chanop = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient outsider = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            chanop.registerAndAwaitWelcome("chanop", "chanop");
            chanop.send("JOIN #secret");
            chanop.readUntil("353", Duration.ofSeconds(5));
            chanop.send("MODE #secret +s");
            chanop.readUntil("MODE #secret +s", Duration.ofSeconds(5));

            outsider.registerAndAwaitWelcome("outsider", "outsider");
            outsider.send("MODE #secret");
            assertThat(outsider.readUntil("403", Duration.ofSeconds(5))).contains("403");
        }
    }

    @Test
    void aRecreatedChannelReportsAFreshCreationTime() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient chanop = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            chanop.registerAndAwaitWelcome("chanop", "chanop");
            chanop.send("JOIN #chan");
            chanop.readUntil("353", Duration.ofSeconds(5));
            chanop.send("MODE #chan");
            chanop.readUntil("324", Duration.ofSeconds(5));
            String firstCreated = chanop.readUntil("329", Duration.ofSeconds(5));

            chanop.send("PART #chan");
            chanop.readUntil("PART #chan", Duration.ofSeconds(5));
            Thread.sleep(1100); // ensure the recreated channel's epoch-second timestamp differs

            chanop.send("JOIN #chan");
            chanop.readUntil("353", Duration.ofSeconds(5));
            chanop.send("MODE #chan");
            chanop.readUntil("324", Duration.ofSeconds(5));
            String secondCreated = chanop.readUntil("329", Duration.ofSeconds(5));

            assertThat(secondCreated).isNotEqualTo(firstCreated);
        }
    }
}
