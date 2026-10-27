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
package net.jircd.capabilities.echomessage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.capability.CapabilityDescriptor;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.extension.capability.DeliveryIntent;
import net.jircd.core.extension.capability.DeliveryRole;
import net.jircd.core.extension.capability.HookDispatcher;
import net.jircd.core.extension.capability.HookKind;
import net.jircd.core.extension.capability.RequestContext;
import net.jircd.core.extension.capability.RuntimeSnapshot;
import net.jircd.core.session.CapabilityTagRenderer;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.MessageDelivery;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** echo-message's sender-copy policy, through core's shared delivery with the real provider. */
class EchoMessageExtensionTest {
    private final NicknameRegistry nicknames = new NicknameRegistry();
    private final ChannelRegistry channels = new ChannelRegistry();
    private final ExtensionRegistry extensions = new ExtensionRegistry();
    private final MessageDelivery delivery =
            new MessageDelivery(channels, nicknames, extensions, () -> "test.jircd.local");
    private final Map<ClientSession, ByteArrayOutputStream> outputs = new HashMap<>();
    private final List<SessionWriter> writers = new ArrayList<>();

    EchoMessageExtensionTest() {
        extensions.register(new EchoMessageExtension());
        extensions.enable(EchoMessageExtension.ID);
    }

    @AfterEach
    void closeWriters() {
        writers.forEach(SessionWriter::close);
    }

    private ClientSession user(String nick, boolean echo) {
        ClientSession session =
                new ClientSession(nick, "host.example", RateLimitBucket.withDefaults());
        session.setNickname(nick);
        session.setIdent("ident");
        if (echo) session.replaceNegotiatedCapabilities(List.of(EchoMessageExtension.ID));
        nicknames.claim(nick, session);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        outputs.put(session, out);
        @SuppressWarnings("PMD.CloseResource") // closed by closeWriters()
        SessionWriter writer =
                new SessionWriter(session, out, new CapabilityTagRenderer(extensions));
        writers.add(writer);
        session.attachWriter(writer);
        return session;
    }

    private MessageDelivery.Delivery send(ClientSession sender, String command, String target) {
        return delivery.deliver(
                sender,
                command,
                target,
                command.equals("TAGMSG") ? null : "hello",
                Map.of(),
                delivery.authorize(sender, target, true),
                recipient -> true);
    }

    private List<String> lines(ClientSession session) {
        writers.forEach(SessionWriter::close);
        return outputs.get(session).toString(StandardCharsets.UTF_8).lines().toList();
    }

    @Test
    void registersOnlyARecipientHook() {
        CapabilityRegistrar registrar = new CapabilityRegistrar("echo-message", "echo-message");
        new EchoMessageExtension().register(registrar);
        CapabilityDescriptor descriptor = registrar.build();
        assertThat(descriptor.registrations()).hasSize(1);
        assertThat(descriptor.registrations().getFirst().kind()).isEqualTo(HookKind.RECIPIENTS);
    }

    @Test
    void theHookAddsASenderCopyAndNothingElseForAnyDelivery() {
        CapabilityRegistrar registrar = new CapabilityRegistrar("echo-message", "echo-message");
        new EchoMessageExtension().register(registrar);
        RuntimeSnapshot snapshot = RuntimeSnapshot.EMPTY.with(registrar.build());
        HookDispatcher dispatcher = new HookDispatcher((provider, kind, category) -> {});
        DeliveryIntent intent =
                new DeliveryIntent(
                        java.util.UUID.randomUUID(), Set.of(DeliveryRole.ORDINARY_TARGET));
        RequestContext context = RequestContext.of(7, Map.of());

        assertThat(dispatcher.recipientRoles(snapshot, Set.of("echo-message"), intent, context))
                .containsExactly(DeliveryRole.SENDER_COPY);
        // The sender gate is central: a sender that did not negotiate the owner is never asked.
        assertThat(dispatcher.recipientRoles(snapshot, Set.of(), intent, context)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRIVMSG", "NOTICE", "TAGMSG"})
    void aChannelMessageIsCopiedToTheSenderOnlyWhenItNegotiatedEcho(String command) {
        ClientSession echoing = user("echoing", true);
        ClientSession plain = user("plain", false);
        ClientSession bob = user("bob", false);
        for (ClientSession member : List.of(echoing, plain, bob)) {
            channels.join("#c", member, ch -> true);
        }

        MessageDelivery.Delivery withEcho = send(echoing, command, "#c");
        MessageDelivery.Delivery withoutEcho = send(plain, command, "#c");

        assertThat(withEcho.recipients().get(echoing)).containsExactly(DeliveryRole.SENDER_COPY);
        assertThat(withoutEcho.recipients()).doesNotContainKey(plain);
        // echoing: its own copy plus plain's message; plain only echoing's message.
        assertThat(lines(echoing)).hasSize(2);
        assertThat(lines(plain)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRIVMSG", "NOTICE", "TAGMSG"})
    void aDirectMessageToSomeoneElseIsCopiedToTheSenderOnlyWithEcho(String command) {
        ClientSession echoing = user("echoing", true);
        ClientSession plain = user("plain", false);
        ClientSession bob = user("bob", false);

        send(echoing, command, "bob");
        send(plain, command, "bob");

        assertThat(lines(echoing)).hasSize(1);
        assertThat(lines(plain)).isEmpty();
        assertThat(lines(bob)).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRIVMSG", "NOTICE", "TAGMSG"})
    void aSelfDirectMessageIsDeliveredExactlyOnceWithOrWithoutEcho(String command) {
        ClientSession echoing = user("echoing", true);
        ClientSession plain = user("plain", false);

        MessageDelivery.Delivery withEcho = send(echoing, command, "echoing");
        send(plain, command, "plain");

        assertThat(withEcho.recipients().get(echoing))
                .containsExactlyInAnyOrder(DeliveryRole.ORDINARY_TARGET, DeliveryRole.SENDER_COPY);
        assertThat(lines(echoing)).hasSize(1);
        assertThat(lines(plain)).hasSize(1);
    }

    @Test
    void aRoleChangeAfterTheSenderSnapshotDoesNotSplitOneDelivery() {
        ClientSession echoing = user("echoing", true);
        ClientSession bob = user("bob", false);
        channels.join("#c", echoing, ch -> true);
        channels.join("#c", bob, ch -> true);

        // The negotiated set is read once for the delivery; a later REQ cannot add a second copy.
        MessageDelivery.Delivery sent = send(echoing, "PRIVMSG", "#c");
        echoing.replaceNegotiatedCapabilities(List.of());

        assertThat(sent.recipients().get(echoing)).containsExactly(DeliveryRole.SENDER_COPY);
        assertThat(lines(echoing)).hasSize(1);
    }

    @Test
    void theRequestContextIsTheOneTheDeliveryCarries() {
        ClientSession echoing = user("echoing", true);
        user("bob", false);
        MessageDelivery.Delivery sent = send(echoing, "PRIVMSG", "bob");
        assertThat(sent.context().requestId()).isPositive();
    }
}
