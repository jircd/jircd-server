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

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MessageSerializerTest {

    @ParameterizedTest
    @ValueSource(ints = {498, 499, 500})
    void commandBudgetIncludesCrLf(int bodyLength) {
        Message message = Message.of(Command.NOTICE, "bob", "x".repeat(bodyLength));
        assertThat(MessageSerializer.commandBytes(message)).isEqualTo(bodyLength + 13);
        if (bodyLength <= 499) {
            assertThat(MessageSerializer.utf8Bytes(MessageSerializer.serialize(message)) + 2)
                    .isEqualTo(bodyLength + 13);
        } else {
            assertThatThrownBy(() -> MessageSerializer.serialize(message))
                    .isInstanceOf(MessageSerializer.MessageTooLongException.class);
        }
    }

    @Test
    void truncationPreservesFourByteCharactersAndAllStructuralFields() throws Exception {
        Message message =
                new Message(
                        Map.of("time", "123"),
                        "sender!ident@host.example",
                        Command.PRIVMSG,
                        "PRIVMSG",
                        List.of("bob", "😀".repeat(124) + "x"));
        String wire = MessageSerializer.serializeText(message);
        Message parsed = MessageParser.parse(wire);
        assertThat(parsed.prefix()).isEqualTo(message.prefix());
        assertThat(parsed.params().getFirst()).isEqualTo("bob");
        assertThat(parsed.tags()).isEqualTo(message.tags());
        String body = parsed.params().getLast();
        assertThat(body).isEqualTo("😀".repeat(117)).doesNotContain("\ufffd");
        assertThat(MessageSerializer.commandBytes(parsed)).isEqualTo(509);
    }

    @Test
    void truncationReservesTheColonEvenIfTheRemainingTextHasNoSpaces() throws Exception {
        Message message = Message.of(Command.NOTICE, "bob", "x".repeat(499) + " tail");
        String wire = MessageSerializer.serializeText(message);
        assertThat(MessageSerializer.utf8Bytes(wire) + 2).isEqualTo(512);
        assertThat(MessageParser.parse(wire).params().getLast()).isEqualTo("x".repeat(498));
    }

    @Test
    void impossibleStructuralFieldsAndNonTextCommandsAreRejected() {
        Message hugeTarget = Message.of(Command.PRIVMSG, "x".repeat(510), "hello");
        assertThatThrownBy(() -> MessageSerializer.serializeText(hugeTarget))
                .isInstanceOf(MessageSerializer.MessageTooLongException.class);
        Message hugeJoin = Message.of(Command.JOIN, "#" + "x".repeat(510));
        assertThatThrownBy(() -> MessageSerializer.serializeText(hugeJoin))
                .isInstanceOf(MessageSerializer.MessageTooLongException.class);
        Message noCharacterFits =
                new Message(
                        Map.of(), "x".repeat(497), Command.NOTICE, "NOTICE", List.of("b", "😀"));
        assertThatThrownBy(() -> MessageSerializer.serializeText(noCharacterFits))
                .isInstanceOf(MessageSerializer.MessageTooLongException.class);
    }

    @Test
    void combinedTagsHave8191BytesIndependentOfThe512ByteCommand() {
        Map<String, String> tags = Map.of("+c", "x".repeat(4091), "s", "y".repeat(4092));
        Message message =
                new Message(tags, null, Command.NOTICE, "NOTICE", List.of("bob", "x".repeat(499)));
        assertThat(MessageSerializer.utf8Bytes(MessageSerializer.serializeText(message)) + 2)
                .isEqualTo(8191 + 512);
        Message oversizedTags =
                new Message(
                        Map.of("+c", "x".repeat(4092), "s", "y".repeat(4092)),
                        null,
                        Command.NOTICE,
                        "NOTICE",
                        List.of("bob", "x".repeat(600)));
        assertThatThrownBy(() -> MessageSerializer.serializeText(oversizedTags))
                .isInstanceOf(MessageSerializer.MessageTooLongException.class)
                .hasMessageContaining("tag section");
    }

    @Test
    void tagDataAccountingUsesEscapedUtf8Bytes() {
        assertThat(MessageSerializer.tagDataBytes(Map.of("+c", " 😀;"))).isEqualTo(11);
    }

    @Test
    void serializesSimpleCommand() {
        Message message = Message.of(Command.JOIN, "#lobby");
        assertThat(MessageSerializer.serialize(message)).isEqualTo("JOIN #lobby");
    }

    @Test
    void serializesPrefixAndTrailingParam() {
        Message message =
                new Message(
                        Map.of(),
                        "alice!ident@host",
                        Command.PRIVMSG,
                        "PRIVMSG",
                        List.of("#lobby", "hello there"));
        assertThat(MessageSerializer.serialize(message))
                .isEqualTo(":alice!ident@host PRIVMSG #lobby :hello there");
    }

    @Test
    void serializesAndEscapesTags() {
        Message message =
                new Message(Map.of("key", "a b;c\\d"), null, Command.NICK, "NICK", List.of("bob"));
        assertThat(MessageSerializer.serialize(message)).isEqualTo("@key=a\\sb\\:c\\\\d NICK bob");
    }

    @Test
    void everyNumericReplyHasA3DigitWireCode() {
        for (NumericReply numeric : NumericReply.values()) {
            assertThat(numeric.wireCode()).hasSize(3);
            assertThat(Integer.parseInt(numeric.wireCode())).isEqualTo(numeric.code());
        }
    }

    // --- Hostmask: nickname grammar ---

    @Test
    void nicknameGrammarBoundaryCases() {
        assertThat(Hostmask.isValidNickname("Alice", 9)).isTrue();
        assertThat(Hostmask.isValidNickname("_special", 9)).isTrue();
        assertThat(Hostmask.isValidNickname("123abc", 9)).isFalse(); // leading digit invalid
        assertThat(Hostmask.isValidNickname("abcdefghi", 9)).isTrue(); // exactly 9
        assertThat(Hostmask.isValidNickname("abcdefghij", 9)).isFalse(); // 10 chars, rejected
        assertThat(Hostmask.isValidNickname("abcde", 4)).isFalse(); // smaller configured maxLength
        assertThat(Hostmask.isValidNickname("abcd", 4)).isTrue();
        assertThat(Hostmask.isValidNickname("", 9)).isFalse();
    }

    // --- ChannelName grammar ---

    @Test
    void channelNameGrammarBoundaryCases() {
        assertThat(ChannelName.isValid("#lobby", 50)).isTrue();
        assertThat(ChannelName.isValid("lobby", 50)).isFalse(); // missing leading #
        assertThat(ChannelName.isValid("#has space", 50)).isFalse();
        assertThat(ChannelName.isValid("#has,comma", 50)).isFalse();
        String exactly50 = "#" + "a".repeat(49);
        assertThat(exactly50).hasSize(50);
        assertThat(ChannelName.isValid(exactly50, 50)).isTrue();
        String fiftyOne = "#" + "a".repeat(50);
        assertThat(ChannelName.isValid(fiftyOne, 50)).isFalse();
        String smallerCeiling = "#" + "a".repeat(9);
        assertThat(ChannelName.isValid(smallerCeiling, 10)).isTrue();
        assertThat(ChannelName.isValid(smallerCeiling + "a", 10)).isFalse();
    }

    // --- Utf8Validator ---

    @Test
    void utf8ValidatorAcceptsValidAndRejectsInvalidSequences() {
        assertThat(
                        Utf8Validator.isValidUtf8(
                                "héllo wörld 🎉".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isTrue();
        byte[] truncatedMultiByte = {(byte) 0xE2, (byte) 0x82}; // incomplete 3-byte sequence
        assertThat(Utf8Validator.isValidUtf8(truncatedMultiByte)).isFalse();
    }

    @Test
    void tagDataBytesAtMostAgreesWithTheExactCountAndRejectsOversizeFromRawLength() {
        Map<String, String> escaped = Map.of("a", "x;y"); // a=x\:y is 6 bytes
        assertThat(MessageSerializer.tagDataBytesAtMost(escaped, 6)).isTrue();
        assertThat(MessageSerializer.tagDataBytesAtMost(escaped, 5)).isFalse();
        assertThat(MessageSerializer.tagDataBytesAtMost(Map.of(), 0)).isTrue();
        assertThat(MessageSerializer.tagDataBytesAtMost(Map.of("a", "é"), 4)).isTrue();
        assertThat(MessageSerializer.tagDataBytesAtMost(Map.of("a", "é"), 3)).isFalse();
        // Longer raw than the limit cannot fit: decided without building the escaped section.
        assertThat(MessageSerializer.tagDataBytesAtMost(Map.of("a", "x".repeat(20_000_000)), 4094))
                .isFalse();
    }
}
