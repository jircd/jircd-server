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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostmaskUsernameContentTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "id\tx",
                "id\u0001x",
                "id\u001fx",
                "id\u007fx",
                "id\u0080x",
                "id\u0085x",
                "id\u009fx",
                "id\0x",
                "id\rx",
                "id\nx",
                "id x",
                "id@x"
            })
    void rejectsControlCharactersAndExistingDelimiters(String username) {
        assertThat(Hostmask.isValidUsernameContent(username)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice", "~ident", "a.b-c_d", "界界", "café"})
    void acceptsOrdinaryAndNonControlNonAsciiContent(String username) {
        assertThat(Hostmask.isValidUsernameContent(username)).isTrue();
    }

    @Test
    void rejectsNullAndEmpty() {
        assertThat(Hostmask.isValidUsernameContent(null)).isFalse();
        assertThat(Hostmask.isValidUsernameContent("")).isFalse();
    }
}
