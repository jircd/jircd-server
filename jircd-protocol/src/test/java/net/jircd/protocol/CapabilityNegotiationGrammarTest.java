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
package net.jircd.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CapabilityNegotiationGrammarTest {

    private static final String SERVER = "irc.example";

    private static Message cap(String... params) {
        return new Message(java.util.Map.of(), null, Command.CAP, "CAP", List.of(params));
    }

    @Test
    void lsVersionIsParsedAsANonnegativeDigitOnlyNumber() {
        assertThat(CapabilityNegotiationGrammar.parseLsVersion(cap("LS", "302"))).isEqualTo(302);
        assertThat(CapabilityNegotiationGrammar.parseLsVersion(cap("LS", "301"))).isEqualTo(301);
        assertThat(CapabilityNegotiationGrammar.parseLsVersion(cap("LS", "0"))).isZero();
        assertThat(CapabilityNegotiationGrammar.parseLsVersion(cap("LS", "000302"))).isEqualTo(302);
    }

    @Test
    void anAbsentEmptyOrMalformedLsVersionCountsAsNoVersion() {
        for (Message message :
                List.of(
                        cap("LS"),
                        cap("LS", ""),
                        cap("LS", "-302"),
                        cap("LS", "+302"),
                        cap("LS", "30x"),
                        cap("LS", "3 02"),
                        cap("LS", "٣٠٢"))) {
            assertThat(CapabilityNegotiationGrammar.parseLsVersion(message))
                    .as("%s", message.params())
                    .isZero();
        }
    }

    @Test
    void aHugeLsVersionSaturatesInsteadOfOverflowing() {
        assertThat(CapabilityNegotiationGrammar.parseLsVersion(cap("LS", "99999999999999999999")))
                .isEqualTo(Integer.MAX_VALUE);
        assertThat(CapabilityNegotiationGrammar.parseLsVersion(cap("LS", "2147483648")))
                .isEqualTo(Integer.MAX_VALUE);
        assertThat(CapabilityNegotiationGrammar.parseLsVersion(cap("LS", "2147483647")))
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void notificationsAreDeduplicatedSortedAndNamesOnly() {
        List<Message> lines =
                CapabilityNegotiationGrammar.notifications(
                        SERVER, "alice", "DEL", List.of("multi-prefix", "batch", "multi-prefix"));
        assertThat(lines).hasSize(1);
        assertThat(MessageSerializer.serializeText(lines.getFirst()))
                .isEqualTo(":irc.example CAP alice DEL :batch multi-prefix");
    }

    @Test
    void anOversizeNotificationSplitsOnTokenBoundariesIntoIndependentlyValidLines() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 80; i++) names.add("vendor.example/capability-" + i);
        List<Message> lines = CapabilityNegotiationGrammar.notifications(SERVER, "*", "NEW", names);
        assertThat(lines).hasSizeGreaterThan(1);
        List<String> seen = new ArrayList<>();
        for (Message line : lines) {
            assertThat(MessageSerializer.commandBytes(line))
                    .isLessThanOrEqualTo(MessageSerializer.MAX_COMMAND_BYTES);
            // No continuation marker: each line is a complete NEW on its own.
            assertThat(line.params()).hasSize(3).startsWith("*", "NEW");
            seen.addAll(List.of(line.params().getLast().split(" ")));
        }
        assertThat(seen).containsExactlyInAnyOrderElementsOf(names);
    }

    @Test
    void aFittingLsIsOneLineForEveryVersion() {
        List<String> offers = List.of("cap-notify", "message-tags");
        for (boolean continuation : List.of(false, true)) {
            List<Message> lines =
                    CapabilityNegotiationGrammar.lsReplies(SERVER, "*", offers, continuation);
            assertThat(lines).hasSize(1);
            assertThat(MessageSerializer.serializeText(lines.getFirst()))
                    .isEqualTo(":irc.example CAP * LS :cap-notify message-tags");
        }
    }

    @Test
    void anOversizeLsUsesTheContinuationMarkerOnEveryLineButTheLast() {
        List<String> offers = new ArrayList<>();
        for (int i = 0; i < 80; i++) offers.add("vendor.example/capability-" + i);
        List<Message> lines = CapabilityNegotiationGrammar.lsReplies(SERVER, "*", offers, true);
        assertThat(lines).hasSizeGreaterThan(1);
        for (int i = 0; i < lines.size(); i++) {
            Message line = lines.get(i);
            assertThat(MessageSerializer.commandBytes(line))
                    .isLessThanOrEqualTo(MessageSerializer.MAX_COMMAND_BYTES);
            if (i < lines.size() - 1) {
                assertThat(line.params()).hasSize(4).startsWith("*", "LS", "*");
            } else {
                assertThat(line.params()).hasSize(3).startsWith("*", "LS");
            }
        }
    }

    @Test
    void aLegacyLsThatCannotFitOneLineIsRejectedRatherThanTruncated() {
        List<String> offers = new ArrayList<>();
        for (int i = 0; i < 80; i++) offers.add("vendor.example/capability-" + i);
        assertThat(CapabilityNegotiationGrammar.fitsOneLine(SERVER, "*", "LS", offers)).isFalse();
        assertThat(CapabilityNegotiationGrammar.fitsOneLine(SERVER, "*", "LS", List.of("a", "b")))
                .isTrue();
        assertThatThrownBy(() -> CapabilityNegotiationGrammar.lsReplies(SERVER, "*", offers, false))
                .isInstanceOf(MessageSerializer.MessageTooLongException.class);
    }

    @Test
    void listPacksLikeLsAndAnEmptyListIsOneEmptyLine() {
        assertThat(CapabilityNegotiationGrammar.listReplies(SERVER, "bob", List.of(), false))
                .singleElement()
                .extracting(MessageSerializer::serializeText)
                .isEqualTo(":irc.example CAP bob LIST :");
        List<String> held = new ArrayList<>();
        for (int i = 0; i < 80; i++) held.add("vendor.example/capability-" + i);
        assertThat(CapabilityNegotiationGrammar.listReplies(SERVER, "bob", held, true))
                .hasSizeGreaterThan(1)
                .allSatisfy(
                        line ->
                                assertThat(MessageSerializer.commandBytes(line))
                                        .isLessThanOrEqualTo(MessageSerializer.MAX_COMMAND_BYTES));
    }

    @Test
    void ackAndNakEchoTheRequestExactlyIncludingOrderAndRepeats() {
        List<String> requested = List.of("b", "-a", "b");
        assertThat(
                        MessageSerializer.serializeText(
                                CapabilityNegotiationGrammar.ack(SERVER, "*", requested)))
                .isEqualTo(":irc.example CAP * ACK :b -a b");
        assertThat(
                        MessageSerializer.serializeText(
                                CapabilityNegotiationGrammar.nak(SERVER, "*", requested)))
                .isEqualTo(":irc.example CAP * NAK :b -a b");
    }

    @Test
    void capNotifyIsTheCoreCapabilityName() {
        assertThat(CapabilityName.CAP_NOTIFY).isEqualTo("cap-notify");
        assertThat(CapabilityNegotiationGrammar.NOTIFY_VERSION).isEqualTo(302);
    }
}
