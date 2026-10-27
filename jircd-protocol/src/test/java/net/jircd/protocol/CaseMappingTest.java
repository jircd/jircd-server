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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CaseMappingTest {

    @ParameterizedTest
    @CsvSource({
        "'','',true",
        "'',*,true",
        "'',****,true",
        "'',?,false",
        "a,'',false",
        "abc,****,true",
        "abc,ab*,true",
        "ab,ab*,true",
        "abc,?bc,true",
        "abc,ab?,true",
        "ab,?ab,false",
        "ab,ab?,false",
        "abc,ab,false",
        "ab,abc,false",
        "abbbc,*ab*bc,true",
        "abbbc,*ab*bd,false",
        "N[]\\^,n{}|~,true"
    })
    void matchesEdgeCases(String text, String mask, boolean expected) {
        assertThat(CaseMapping.matches(text, mask)).isEqualTo(expected);
    }

    @Test
    void agreesWithRecursiveMatcherForGeneratedShortInputs() {
        Random random = new Random(89);
        String alphabet = "aAbB[]{}\\|^~*?";
        for (int example = 0; example < 30_000; example++) {
            String text = randomString(random, alphabet);
            String mask = randomString(random, alphabet);
            assertThat(CaseMapping.matches(text, mask))
                    .as("text <%s>, mask <%s>", text, mask)
                    .isEqualTo(
                            recursiveMatch(CaseMapping.fold(text), CaseMapping.fold(mask), 0, 0));
        }
    }

    @Test
    void rejectsPathologicalMaskWithinOneSecond() {
        assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () ->
                        assertThat(CaseMapping.matches("a".repeat(400), "*a*a*a*a*a*a*a*a*a*b"))
                                .isFalse());
    }

    private static String randomString(Random random, String alphabet) {
        int length = random.nextInt(9);
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            result.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return result.toString();
    }

    // Original production matcher retained as an oracle for bounded short inputs only.
    private static boolean recursiveMatch(String text, String pattern, int ti, int pi) {
        while (pi < pattern.length() && pattern.charAt(pi) != '*') {
            if (ti >= text.length()) {
                return false;
            }
            char pc = pattern.charAt(pi);
            if (pc != '?' && pc != text.charAt(ti)) {
                return false;
            }
            ti++;
            pi++;
        }
        if (pi == pattern.length()) {
            return ti == text.length();
        }
        for (int nextTi = ti; nextTi <= text.length(); nextTi++) {
            if (recursiveMatch(text, pattern, nextTi, pi + 1)) {
                return true;
            }
        }
        return false;
    }
}
