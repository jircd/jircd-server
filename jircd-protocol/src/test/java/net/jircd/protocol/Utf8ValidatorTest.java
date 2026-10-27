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

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Utf8ValidatorTest {

    @Test
    void emptyByteArrayIsValid() {
        assertThat(Utf8Validator.isValidUtf8(new byte[0])).isTrue();
    }

    @Test
    void plainAsciiIsValid() {
        assertThat(Utf8Validator.isValidUtf8("hello world".getBytes(StandardCharsets.UTF_8)))
                .isTrue();
    }

    @Test
    void multiByteUtf8CharactersAreValid() {
        // "café" (Latin-1 combining) and a CJK character, each requiring multi-byte encoding.
        assertThat(Utf8Validator.isValidUtf8("café 你好".getBytes(StandardCharsets.UTF_8))).isTrue();
    }

    @Test
    void truncatedMultiByteSequenceIsInvalid() {
        // 0xC3 alone starts a two-byte sequence but is never followed by its continuation byte.
        byte[] truncated = {(byte) 0xC3};
        assertThat(Utf8Validator.isValidUtf8(truncated)).isFalse();
    }

    @Test
    void overlongEncodingIsInvalid() {
        // 0xC0 0x80 is a rejected overlong encoding of NUL, not valid UTF-8.
        byte[] overlong = {(byte) 0xC0, (byte) 0x80};
        assertThat(Utf8Validator.isValidUtf8(overlong)).isFalse();
    }

    @Test
    void unexpectedContinuationByteIsInvalid() {
        // 0x80 is a continuation byte with no preceding lead byte.
        byte[] strayContinuation = {(byte) 0x80};
        assertThat(Utf8Validator.isValidUtf8(strayContinuation)).isFalse();
    }

    @Test
    void isolatedSurrogateEncodingIsInvalid() {
        // 0xED 0xA0 0x80 encodes U+D800, a UTF-16 surrogate that is not a valid UTF-8 code point.
        byte[] surrogate = {(byte) 0xED, (byte) 0xA0, (byte) 0x80};
        assertThat(Utf8Validator.isValidUtf8(surrogate)).isFalse();
    }
}
