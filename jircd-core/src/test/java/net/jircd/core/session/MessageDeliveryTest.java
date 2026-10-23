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
package net.jircd.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.extension.capability.DeliveryRole;
import net.jircd.core.extension.capability.InboundTagResult;
import net.jircd.core.extension.capability.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The shared target and send gates, role merging and per-envelope identity of every message. */
class MessageDeliveryTest {

  /** Sender-copy and request-correlation stand-in for providers that live outside core. */
  private static final class Provider extends AbstractCapabilityExtension {
    final AtomicInteger recipientCalls = new AtomicInteger();
    final AtomicInteger inboundCalls = new AtomicInteger();
    final AtomicReference<RequestContext> seenContext = new AtomicReference<>();

    Provider() {
      super("copy");
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
      registrar.onInboundTags(
          (tags, context) -> {
            inboundCalls.incrementAndGet();
            Map<String, String> forwarded = new LinkedHashMap<>();
            tags.tags().entrySet().stream()
                .filter(e -> e.getKey().startsWith("+"))
                .forEach(e -> forwarded.put(e.getKey(), e.getValue()));
            return new InboundTagResult(forwarded, Map.of("copy/label", "L1"));
          });
      registrar.onRecipients(
          (intent, context) -> {
            recipientCalls.incrementAndGet();
            seenContext.set(context);
            return Set.of(DeliveryRole.SENDER_COPY);
          });
      registrar.onOutboundTags(
          Set.of("msgid"), 42, message -> Map.of("msgid", message.messageId().toString()));
      registrar.onOutboundTags(
          Set.of("time"), 64, message -> Map.of("time", message.sentAt().toString()));
    }
  }

  private final NicknameRegistry nicknames = new NicknameRegistry();
  private final ChannelRegistry channels = new ChannelRegistry();
  private final ExtensionRegistry extensions = new ExtensionRegistry();
  private final Map<ClientSession, ByteArrayOutputStream> outputs = new LinkedHashMap<>();
  private final List<SessionWriter> writers = new ArrayList<>();
  private MessageDelivery delivery;

  @BeforeEach
  void setUp() {
    delivery = new MessageDelivery(channels, nicknames, extensions, () -> "test.jircd.local");
  }

  @AfterEach
  void closeWriters() {
    writers.forEach(SessionWriter::close);
  }

  private ClientSession user(String nick, String... negotiated) {
    ClientSession session = new ClientSession(nick, "host.example", RateLimitBucket.withDefaults());
    session.setNickname(nick);
    session.setIdent("ident");
    session.replaceNegotiatedCapabilities(List.of(negotiated));
    nicknames.claim(nick, session);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    outputs.put(session, out);
    @SuppressWarnings("PMD.CloseResource") // closed by closeWriters()
    SessionWriter writer = new SessionWriter(session, out, new CapabilityTagRenderer(extensions));
    writers.add(writer);
    session.attachWriter(writer);
    return session;
  }

  private void join(ClientSession session, String channel) {
    channels.join(channel, session, ch -> true);
  }

  private String received(ClientSession session) {
    writers.forEach(SessionWriter::close);
    return outputs.get(session).toString(StandardCharsets.UTF_8);
  }

  private void enable(Provider provider) {
    extensions.register(provider);
    extensions.enable(provider.id());
  }

  // --- target and send gates ---

  @Test
  void anUnknownNicknameIsReportedUnlessTheCommandIsSilent() {
    ClientSession alice = user("alice");

    assertThat(delivery.authorize(alice, "ghost", true)).isEmpty();
    assertThat(delivery.authorize(alice, "ghost", false)).isEmpty();

    assertThat(received(alice)).contains(" 401 alice ghost ").hasLineCount(1);
  }

  @Test
  void aMissingChannelIsReportedAsCannotSend() {
    ClientSession alice = user("alice");

    assertThat(delivery.authorize(alice, "#nowhere", true)).isEmpty();

    assertThat(received(alice)).contains(" 404 alice #nowhere ");
  }

  @Test
  void membersOnlyAndModeratedGatesBlockOutsidersAndUnvoicedMembers() {
    ClientSession op = user("op");
    ClientSession outsider = user("outsider");
    ClientSession member = user("member");
    join(op, "#c");
    join(member, "#c");
    Channel channel = channels.lookup("#c").orElseThrow();
    channel.setMode(CoreChannelModes.MEMBERS_ONLY);

    assertThat(delivery.authorize(outsider, "#c", false)).isEmpty();
    assertThat(delivery.authorize(member, "#c", false)).contains(op, member);

    channel.setMode(CoreChannelModes.MODERATED);
    assertThat(delivery.authorize(member, "#c", false)).isEmpty();
    assertThat(delivery.authorize(op, "#c", false)).contains(op, member);
    channel.grantVoice(member);
    assertThat(delivery.authorize(member, "#c", false)).contains(op, member);
  }

  @Test
  void aBanOnThePresentedIdentityMutesTheSenderUnlessExcepted() {
    ClientSession op = user("op");
    ClientSession banned = user("banned");
    join(op, "#c");
    join(banned, "#c");
    Channel channel = channels.lookup("#c").orElseThrow();
    channel.addBan(new BanEntry("banned!*@*", "op", Instant.EPOCH));

    assertThat(delivery.authorize(banned, "#c", false)).isEmpty();
    assertThat(delivery.authorize(op, "#c", false)).contains(banned);
    assertThat(received(banned)).isEmpty();
  }

  // --- authorization cannot be bypassed ---

  @Test
  void anAuthorizationForOneTargetCannotDeliverToAnotherOrFromAnotherSender() {
    ClientSession alice = user("alice");
    ClientSession bob = user("bob");
    ClientSession mallory = user("mallory");
    join(alice, "#c");
    join(bob, "#c");
    MessageDelivery.AuthorizedTarget forChannel = delivery.authorize(alice, "#c", true);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                delivery.deliver(
                    alice, "PRIVMSG", "#does-not-exist", "hi", Map.of(), forChannel, r -> true))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> delivery.deliver(mallory, "PRIVMSG", "#c", "hi", Map.of(), forChannel, r -> true))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(received(bob)).isEmpty();
    assertThat(received(mallory)).isEmpty();
  }

  @Test
  void anAuthorizationFromAnotherDeliveryServiceIsRefused() {
    ClientSession alice = user("alice");
    ClientSession bob = user("bob");
    MessageDelivery other = new MessageDelivery(channels, nicknames, extensions, () -> "s");

    MessageDelivery.AuthorizedTarget foreign = other.authorize(alice, "bob", true);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> delivery.deliver(alice, "PRIVMSG", "bob", "hi", Map.of(), foreign, r -> true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(received(bob)).isEmpty();
  }

  @Test
  void aFailedAuthorizationDeliversNothingEvenWhenDeliveredAnyway() {
    ClientSession alice = user("alice");
    ClientSession bob = user("bob");
    MessageDelivery.AuthorizedTarget denied = delivery.authorize(alice, "#does-not-exist", false);

    MessageDelivery.Delivery sent =
        delivery.deliver(alice, "PRIVMSG", "#does-not-exist", "hi", Map.of(), denied, r -> true);

    assertThat(denied).isEmpty();
    assertThat(sent.recipients()).isEmpty();
    assertThat(received(bob)).isEmpty();
  }

  // --- recipients and roles ---

  @Test
  void aChannelMessageSkipsTheSenderByDefault() {
    ClientSession alice = user("alice");
    ClientSession bob = user("bob");
    join(alice, "#c");
    join(bob, "#c");

    MessageDelivery.Delivery sent =
        delivery.deliver(
            alice,
            "PRIVMSG",
            "#c",
            "hi",
            Map.of(),
            delivery.authorize(alice, "#c", true),
            r -> true);

    assertThat(sent.recipients()).containsOnlyKeys(bob);
    assertThat(sent.recipients().get(bob)).containsExactly(DeliveryRole.ORDINARY_TARGET);
    assertThat(received(alice)).isEmpty();
    assertThat(received(bob)).isEqualTo(":alice!ident@host.example PRIVMSG #c hi\r\n");
  }

  @Test
  void aDirectMessageToTheSenderIsDeliveredExactlyOnceWithOrWithoutSenderCopy() {
    enable(new Provider());
    ClientSession plain = user("plain");
    ClientSession echo = user("echo", "copy");

    delivery.deliver(
        plain,
        "PRIVMSG",
        "plain",
        "me",
        Map.of(),
        delivery.authorize(plain, "plain", true),
        r -> true);
    delivery.deliver(
        echo, "PRIVMSG", "echo", "me", Map.of(), delivery.authorize(echo, "echo", true), r -> true);

    assertThat(received(plain).lines()).hasSize(1);
    assertThat(received(echo).lines()).hasSize(1);
  }

  @Test
  void senderCopyMergesWithTheOrdinaryRoleAndIsOmittedWithoutTheOwnerNegotiated() {
    Provider provider = new Provider();
    enable(provider);
    ClientSession echoing = user("echoing", "copy");
    ClientSession silent = user("silent");
    ClientSession bob = user("bob");
    join(echoing, "#c");
    join(silent, "#c");
    join(bob, "#c");

    MessageDelivery.Delivery withCopy =
        delivery.deliver(
            echoing,
            "PRIVMSG",
            "#c",
            "a",
            Map.of(),
            delivery.authorize(echoing, "#c", true),
            r -> true);
    MessageDelivery.Delivery without =
        delivery.deliver(
            silent,
            "PRIVMSG",
            "#c",
            "b",
            Map.of(),
            delivery.authorize(silent, "#c", true),
            r -> true);

    assertThat(withCopy.recipients().get(echoing)).containsExactly(DeliveryRole.SENDER_COPY);
    assertThat(withCopy.recipients().get(bob)).containsExactly(DeliveryRole.ORDINARY_TARGET);
    assertThat(without.recipients()).doesNotContainKey(silent);
    assertThat(received(echoing).lines()).hasSize(2); // its own copy and silent's message
  }

  @Test
  void aDirectMessageToSomeoneElseIsCopiedToTheSenderOnlyWithTheOwner() {
    enable(new Provider());
    ClientSession echoing = user("echoing", "copy");
    ClientSession bob = user("bob");

    MessageDelivery.Delivery sent =
        delivery.deliver(
            echoing,
            "NOTICE",
            "bob",
            "x",
            Map.of(),
            delivery.authorize(echoing, "bob", true),
            r -> true);

    assertThat(sent.recipients()).containsOnlyKeys(bob, echoing);
    assertThat(sent.recipients().get(echoing)).containsExactly(DeliveryRole.SENDER_COPY);
  }

  @Test
  void theRecipientGateAppliesToTheSenderCopyToo() {
    enable(new Provider());
    ClientSession echoing = user("echoing", "copy");
    ClientSession tagged = user("tagged", "tagged");
    join(echoing, "#c");
    join(tagged, "#c");

    MessageDelivery.Delivery sent =
        delivery.deliver(
            echoing,
            "TAGMSG",
            "#c",
            null,
            Map.of("+x", "y"),
            delivery.authorize(echoing, "#c", true),
            r -> r.negotiatedCapabilities().contains("tagged"));

    assertThat(sent.recipients()).containsOnlyKeys(tagged);
  }

  // --- envelope identity and request correlation ---

  @Test
  void everyRecipientSeesTheSameEnvelopeIdentityAndTheHooksRunOncePerDelivery() {
    Provider provider = new Provider();
    enable(provider);
    ClientSession sender = user("sender", "copy");
    List<ClientSession> peers = new ArrayList<>();
    join(sender, "#c");
    for (int i = 0; i < 20; i++) {
      ClientSession peer = user("peer" + i, "copy");
      peers.add(peer);
      join(peer, "#c");
    }

    MessageDelivery.Delivery sent =
        delivery.deliver(
            sender,
            "PRIVMSG",
            "#c",
            "hello",
            Map.of(),
            delivery.authorize(sender, "#c", true),
            r -> true);

    String msgid = sent.envelope().messageId().toString();
    String time = sent.envelope().sentAt().toString();
    assertThat(peers)
        .isNotEmpty()
        .allSatisfy(
            peer -> assertThat(received(peer)).contains("msgid=" + msgid).contains("time=" + time));
    assertThat(received(sender)).contains("msgid=" + msgid);
    assertThat(provider.inboundCalls).hasValue(1);
    assertThat(provider.recipientCalls).hasValue(1);
  }

  @Test
  void requestContextReachesTheRecipientHookButNeverTheEnvelopeOrPeers() {
    Provider provider = new Provider();
    enable(provider);
    ClientSession sender = user("sender", "copy");
    ClientSession peer = user("peer", "copy");
    join(sender, "#c");
    join(peer, "#c");

    MessageDelivery.Delivery sent =
        delivery.deliver(
            sender,
            "PRIVMSG",
            "#c",
            "hi",
            Map.of(),
            delivery.authorize(sender, "#c", true),
            r -> true);

    assertThat(provider.seenContext.get()).isSameAs(sent.context());
    assertThat(sent.context().attributes()).containsEntry("copy/label", "L1");
    assertThat(received(peer)).doesNotContain("L1").doesNotContain("label");
    assertThat(sent.envelope().clientTags()).doesNotContainKey("copy/label");
  }

  @Test
  void eachRequestGetsItsOwnContextId() {
    ClientSession alice = user("alice");
    ClientSession bob = user("bob");

    long first =
        delivery
            .deliver(
                alice,
                "PRIVMSG",
                "bob",
                "1",
                Map.of(),
                delivery.authorize(alice, "bob", true),
                r -> true)
            .context()
            .requestId();
    long second =
        delivery
            .deliver(
                alice,
                "PRIVMSG",
                "bob",
                "2",
                Map.of(),
                delivery.authorize(alice, "bob", true),
                r -> true)
            .context()
            .requestId();

    assertThat(second).isGreaterThan(first);
    assertThat(bob).isNotNull();
  }

  @Test
  void withoutAnInboundTagProviderNoClientTagIsForwarded() {
    ClientSession alice = user("alice");
    user("bob", "message-tags");

    MessageDelivery.Delivery sent =
        delivery.deliver(
            alice,
            "PRIVMSG",
            "bob",
            "hi",
            Map.of("+typing", "active"),
            delivery.authorize(alice, "bob", true),
            r -> true);

    assertThat(sent.envelope().clientTags()).isEmpty();
  }

  /** Selects client-only tags, as message-tags does; named so a recipient can negotiate it. */
  private static final class Forwarder extends AbstractCapabilityExtension {
    Forwarder() {
      super("fwd");
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
      registrar.onInboundTags(
          (tags, context) ->
              new InboundTagResult(Map.of("+private", tags.tags().get("+private")), Map.of()));
    }
  }

  /** A second inbound-tag provider that selects nothing and only records context. */
  private static final class ContextOnly extends AbstractCapabilityExtension {
    ContextOnly() {
      super("ctx");
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
      registrar.onInboundTags(
          (tags, context) -> new InboundTagResult(Map.of(), Map.of("ctx/x", "1")));
    }
  }

  /** Selects the same tag as {@link Forwarder}, so two capabilities own one key. */
  private static final class SecondForwarder extends AbstractCapabilityExtension {
    SecondForwarder() {
      super("fwd2");
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
      registrar.onInboundTags(
          (tags, context) ->
              new InboundTagResult(Map.of("+private", tags.tags().get("+private")), Map.of()));
    }
  }

  @Test
  void aTagSelectedByTwoCapabilitiesReachesRecipientsOfEitherOne() {
    extensions.register(new Forwarder());
    extensions.enable("fwd");
    extensions.register(new SecondForwarder());
    extensions.enable("fwd2");
    ClientSession sender = user("sender");
    ClientSession first = user("first", "fwd");
    ClientSession second = user("second", "fwd2");
    ClientSession neither = user("neither");
    for (ClientSession member : List.of(sender, first, second, neither)) join(member, "#c");

    delivery.deliver(
        sender,
        "PRIVMSG",
        "#c",
        "hi",
        Map.of("+private", "secret"),
        delivery.authorize(sender, "#c", true),
        r -> true);

    assertThat(received(first)).contains("+private=secret");
    assertThat(received(second)).contains("+private=secret");
    assertThat(received(neither)).doesNotContain("private");
  }

  @Test
  void forwardedClientTagsGoOnlyToRecipientsOfTheProviderThatSelectedThem() {
    extensions.register(new Forwarder());
    extensions.enable("fwd");
    extensions.register(new ContextOnly());
    extensions.enable("ctx");
    ClientSession sender = user("sender");
    ClientSession owner = user("owner", "fwd");
    ClientSession other = user("other", "ctx");
    join(sender, "#c");
    join(owner, "#c");
    join(other, "#c");

    delivery.deliver(
        sender,
        "PRIVMSG",
        "#c",
        "hi",
        Map.of("+private", "secret"),
        delivery.authorize(sender, "#c", true),
        r -> true);

    assertThat(received(owner)).contains("+private=secret");
    assertThat(received(other)).doesNotContain("private").doesNotContain("secret");
  }
}
