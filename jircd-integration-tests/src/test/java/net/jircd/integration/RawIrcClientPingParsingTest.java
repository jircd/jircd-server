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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RawIrcClientPingParsingTest {

    @ParameterizedTest
    @MethodSource("pingLines")
    void extractsOnlyThePingCommandParameter(String line, String expectedToken) {
        assertThat(RawIrcClient.serverPingToken(line)).isEqualTo(expectedToken);
    }

    private static Stream<Arguments> pingLines() {
        return Stream.of(
                Arguments.of("PING :tok", "tok"),
                Arguments.of("PING tok", "tok"),
                Arguments.of(":srv PING :tok", "tok"),
                Arguments.of("@time=123 :srv PING :tok", "tok"),
                Arguments.of("PING :token with spaces", "token with spaces"),
                Arguments.of("PING ::x", ":x"),
                Arguments.of("ping tok", "tok"),
                Arguments.of("PING a b", "a"),
                Arguments.of("PING", null),
                Arguments.of("PING :", null),
                Arguments.of("@time=123", null),
                Arguments.of(":srv", null),
                Arguments.of(":alice PRIVMSG #c :hi PING x", null),
                Arguments.of(":PING!user@host PRIVMSG #c :hello", null));
    }
}
