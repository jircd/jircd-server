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

import org.junit.jupiter.api.Test;

class MessageParserTest {

    @Test
    void parsesSimpleCommandWithParams() throws MalformedMessageException {
        Message message = MessageParser.parse("JOIN #lobby");
        assertThat(message.command()).isEqualTo(Command.JOIN);
        assertThat(message.params()).containsExactly("#lobby");
        assertThat(message.prefix()).isNull();
    }

    @Test
    void parsesPrefixAndTrailingParam() throws MalformedMessageException {
        Message message = MessageParser.parse(":alice!ident@host PRIVMSG #lobby :hello there");
        assertThat(message.prefix()).isEqualTo("alice!ident@host");
        assertThat(message.command()).isEqualTo(Command.PRIVMSG);
        assertThat(message.params()).containsExactly("#lobby", "hello there");
    }

    @Test
    void parsesMessageTags() throws MalformedMessageException {
        Message message =
                MessageParser.parse(
                        "@msgid=abc123;time=2024-01-01T00:00:00.000Z PRIVMSG #lobby :hi");
        assertThat(message.tags())
                .containsEntry("msgid", "abc123")
                .containsEntry("time", "2024-01-01T00:00:00.000Z");
    }

    @Test
    void unescapesTagValues() throws MalformedMessageException {
        Message message = MessageParser.parse("@key=a\\sb\\:c\\\\d NICK bob");
        assertThat(message.tags()).containsEntry("key", "a b;c\\d");
    }

    @Test
    void commandMatchingIsCaseInsensitive() throws MalformedMessageException {
        assertThat(MessageParser.parse("join #x").command()).isEqualTo(Command.JOIN);
        assertThat(MessageParser.parse("Join #x").command()).isEqualTo(Command.JOIN);
        assertThat(MessageParser.parse("JOIN #x").command()).isEqualTo(Command.JOIN);
    }

    @Test
    void unrecognizedCommandTokenYieldsNullCommandButKeepsRawToken()
            throws MalformedMessageException {
        Message message = MessageParser.parse("BOGUSCOMMAND foo");
        assertThat(message.command()).isNull();
        assertThat(message.rawCommand()).isEqualTo("BOGUSCOMMAND");
    }

    @Test
    void emptyLineIsMalformed() {
        assertThatThrownBy(() -> MessageParser.parse(""))
                .isInstanceOf(MalformedMessageException.class);
    }

    @Test
    void prefixWithNoCommandIsMalformed() {
        assertThatThrownBy(() -> MessageParser.parse(":onlyprefix"))
                .isInstanceOf(MalformedMessageException.class);
    }

    @Test
    void everyFullCommandCatalogEntryParsesToARecognizedCommand() throws MalformedMessageException {
        for (Command command : Command.values()) {
            Message message = MessageParser.parse(command.name() + " arg1 arg2");
            assertThat(message.command()).as("command %s", command).isEqualTo(command);
        }
    }
}
