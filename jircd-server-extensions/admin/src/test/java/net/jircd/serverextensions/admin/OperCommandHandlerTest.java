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
package net.jircd.serverextensions.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.password4j.Password;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import net.jircd.core.config.ConfigurationLoader;
import net.jircd.core.config.ConfigurationReloader;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.DisconnectCleanup;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.TagRenderer;
import net.jircd.core.session.WhowasHistory;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.api.Test;

class OperCommandHandlerTest {
    private static final String HASH = Password.hash("correct").withBcrypt().getResult();

    @Test
    void absentAddressUsesIndependentConnectionKeysAndStillLimitsOper() throws Exception {
        var handler = handler(new OperAttemptTracker(Clock.systemUTC(), 10));
        ClientSession first = session("first");
        ClientSession second = session("second");
        handler.handle(first, MessageParser.parse("OPER root wrong"));
        assertThat(first.lifecycle().isClosing()).isFalse();
        handler.handle(second, MessageParser.parse("OPER root correct"));
        assertThat(second.isAdministrator()).isTrue();
        handler.handle(first, MessageParser.parse("OPER root wrong"));
        assertThat(first.lifecycle().isClosing()).isTrue();
    }

    @Test
    void fullTrackerVerifiesCredentialsAndLimitsFailuresPerSession() throws Exception {
        var tracker = new OperAttemptTracker(Clock.systemUTC(), 1);
        tracker.authenticate("occupied", 1, 300, true, () -> false);
        var handler = handler(tracker);
        ClientSession first = session("first");
        ClientSession second = session("second");
        handler.handle(first, MessageParser.parse("OPER root wrong"));
        assertThat(first.lifecycle().isClosing()).isFalse();
        handler.handle(second, MessageParser.parse("OPER root correct"));
        assertThat(second.isAdministrator()).isTrue();
        handler.handle(first, MessageParser.parse("OPER root wrong"));
        assertThat(first.lifecycle().isClosing()).isTrue();
        assertThat(tracker.authenticate("occupied", 1, 300, true, () -> true))
                .isEqualTo(OperAttemptTracker.Result.LOCKED);
    }

    @Test
    void successfulFallbackOperResetsTheSessionCounter() throws Exception {
        var tracker = new OperAttemptTracker(Clock.systemUTC(), 1);
        tracker.authenticate("occupied", 1, 300, true, () -> false);
        var handler = handler(tracker);
        ClientSession session = session("first");
        handler.handle(session, MessageParser.parse("OPER root wrong"));
        handler.handle(session, MessageParser.parse("OPER root correct"));
        handler.handle(session, MessageParser.parse("OPER root wrong"));
        assertThat(session.lifecycle().isClosing()).isFalse();
    }

    @Test
    void sourceAddressIsIndependentOfDisplayHostname() throws Exception {
        var handler = handler(new OperAttemptTracker(Clock.systemUTC(), 10));
        var address = InetAddress.ofLiteral("192.0.2.1");
        var first =
                new ClientSession(
                        "first",
                        "display.example",
                        new RateLimitBucket(20, 10, Clock.systemUTC()),
                        true,
                        address);
        var second =
                new ClientSession(
                        "second",
                        "unknown",
                        new RateLimitBucket(20, 10, Clock.systemUTC()),
                        true,
                        address);
        handler.handle(first, MessageParser.parse("OPER root wrong"));
        handler.handle(second, MessageParser.parse("OPER root wrong"));
        assertThat(first.sourceAddress()).isEqualTo(address);
        assertThat(second.lifecycle().isClosing()).isTrue();
    }

    @Test
    void tlsRequirementStillAppliesWhenTrackerIsFull() throws Exception {
        var tracker = new OperAttemptTracker(Clock.systemUTC(), 1);
        tracker.authenticate("occupied", 1, 300, true, () -> false);
        var handler = handler(tracker, "operRequireTls: true\n");
        var plain =
                new ClientSession(
                        "plain", "unknown", new RateLimitBucket(20, 10, Clock.systemUTC()));
        var replies = new ByteArrayOutputStream();
        try (var writer = new SessionWriter(plain, replies, TagRenderer.NONE)) {
            plain.attachWriter(writer);
            handler.handle(plain, MessageParser.parse("OPER root correct"));
            handler.handle(plain, MessageParser.parse("OPER root wrong"));
            handler.handle(plain, MessageParser.parse("OPER root wrong"));
        }
        assertThat(replies.toString(StandardCharsets.UTF_8))
                .contains(" 491 * :OPER requires TLS; reconnect using TLS");
        assertThat(plain.isAdministrator()).isFalse();
        assertThat(plain.lifecycle().isClosing()).isFalse();
    }

    @Test
    void ipv6AddressesInTheSamePrefixShareHandlerLockout() throws Exception {
        assertSharedHandlerLockout(
                InetAddress.ofLiteral("2001:db8::1"), InetAddress.ofLiteral("2001:db8::ffff"));
    }

    @Test
    void ipv4AndMappedIpv4ShareHandlerLockout() throws Exception {
        var mapped =
                Inet6Address.getByAddress(
                        null,
                        new byte[] {
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            (byte) 0xff,
                            (byte) 0xff,
                            (byte) 192,
                            0,
                            2,
                            1
                        },
                        -1);
        assertSharedHandlerLockout(InetAddress.ofLiteral("192.0.2.1"), mapped);
    }

    private static void assertSharedHandlerLockout(
            InetAddress firstAddress, InetAddress secondAddress) throws Exception {
        var handler = handler(new OperAttemptTracker(Clock.systemUTC(), 10));
        var first =
                new ClientSession(
                        "first",
                        "display.example",
                        new RateLimitBucket(20, 10, Clock.systemUTC()),
                        true,
                        firstAddress);
        var second =
                new ClientSession(
                        "second",
                        "other.example",
                        new RateLimitBucket(20, 10, Clock.systemUTC()),
                        true,
                        secondAddress);
        var third =
                new ClientSession(
                        "third",
                        "unknown",
                        new RateLimitBucket(20, 10, Clock.systemUTC()),
                        true,
                        secondAddress);
        handler.handle(first, MessageParser.parse("OPER root wrong"));
        assertThat(first.lifecycle().isClosing()).isFalse();
        handler.handle(second, MessageParser.parse("OPER root wrong"));
        assertThat(second.lifecycle().isClosing()).isTrue();
        handler.handle(third, MessageParser.parse("OPER root correct"));
        assertThat(third.isAdministrator()).isFalse();
        assertThat(third.lifecycle().isClosing()).isTrue();
    }

    private static ClientSession session(String id) {
        return new ClientSession(
                id, "unknown", new RateLimitBucket(20, 10, Clock.systemUTC()), true);
    }

    private static OperCommandHandler handler(OperAttemptTracker tracker) throws Exception {
        return handler(tracker, "");
    }

    private static OperCommandHandler handler(OperAttemptTracker tracker, String extraYaml)
            throws Exception {
        var loader = new ConfigurationLoader(Set.of(), Set.of());
        var config =
                loader.load(
                        new ByteArrayInputStream(
                                ("operFailureThreshold: 2\nadministratorCredentials:\n"
                                                + "  - username: root\n    hashedPassword: '"
                                                + HASH
                                                + "'\n"
                                                + extraYaml)
                                        .getBytes(StandardCharsets.UTF_8)));
        var registry = new ExtensionRegistry();
        return new OperCommandHandler(
                () -> "test.local",
                new ConfigurationReloader(Path.of("unused.yaml"), loader, registry, config),
                new DisconnectCleanup(
                        new NicknameRegistry(),
                        new ChannelRegistry(),
                        new WhowasHistory(() -> 100),
                        registry),
                tracker);
    }
}
