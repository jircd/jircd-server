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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.HostnameDisplayExtension;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.ServerExtension;
import net.jircd.core.session.command.JoinCommandHandler;
import net.jircd.core.session.command.TopicCommandHandler;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.api.Test;

class TopicMetadataHandlerTest {
  @Test
  void queryAndJoinReturnTheStoredPresentedSetterImmediatelyAfterTheTopic() throws Exception {
    ChannelRegistry registry = new ChannelRegistry();
    ExtensionRegistry extensions = new ExtensionRegistry();
    extensions.register(new FixedCloak());
    extensions.enable("fixed-cloak");
    ClientSession setter = session("setter");
    Channel channel = registry.join("#c", setter, ignored -> true).orElseThrow().channel();
    assertThat(channel.topicState()).isNull();
    new TopicCommandHandler(
            registry,
            extensions,
            () -> "srv",
            () -> 390,
            Clock.fixed(Instant.ofEpochSecond(1600000000L), ZoneOffset.UTC))
        .handle(setter, MessageParser.parse("TOPIC #c :old topic"));
    TopicCommandHandler topic =
        new TopicCommandHandler(
            registry,
            extensions,
            () -> "srv",
            () -> 390,
            Clock.fixed(Instant.ofEpochSecond(1700000000L), ZoneOffset.UTC));
    topic.handle(setter, MessageParser.parse("TOPIC #c :a topic"));
    assertThat(channel.topicState())
        .isEqualTo(new Channel.Topic("a topic", "setter!setter@cloaked.example", 1700000000L));
    topic.handle(setter, MessageParser.parse("TOPIC #c :" + "x".repeat(391)));
    assertThat(channel.topicState())
        .isEqualTo(new Channel.Topic("a topic", "setter!setter@cloaked.example", 1700000000L));
    setter.setNickname("renamed");
    extensions.disable("fixed-cloak");
    ClientSession viewer = session("viewer");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(viewer, out, TagRenderer.NONE)) {
      viewer.attachWriter(writer);
      topic.handle(viewer, MessageParser.parse("TOPIC #c"));
      new JoinCommandHandler(registry, extensions, () -> "srv", () -> 50)
          .handle(viewer, MessageParser.parse("JOIN #c"));
    }
    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains(
            ":srv 332 viewer #c :a topic\r\n:srv 333 viewer #c setter!setter@cloaked.example 1700000000\r\n")
        .doesNotContain("renamed!", "setter@host.example");
    assertThat(out.toString(StandardCharsets.UTF_8).lines().filter(l -> l.contains(" 333 ")))
        .hasSize(2);
  }

  @Test
  void clearingATopicMakesQueryAndJoinOmitTopicMetadata() throws Exception {
    ChannelRegistry registry = new ChannelRegistry();
    ExtensionRegistry extensions = new ExtensionRegistry();
    ClientSession setter = session("setter");
    Channel channel = registry.join("#c", setter, ignored -> true).orElseThrow().channel();
    TopicCommandHandler topic =
        new TopicCommandHandler(
            registry,
            extensions,
            () -> "srv",
            () -> 390,
            Clock.fixed(Instant.ofEpochSecond(1700000000L), ZoneOffset.UTC));
    topic.handle(setter, MessageParser.parse("TOPIC #c :old"));
    topic.handle(setter, MessageParser.parse("TOPIC #c :"));
    assertThat(channel.topic()).isNull();
    assertThat(channel.topicState()).isNull();
    ClientSession viewer = session("viewer");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(viewer, out, TagRenderer.NONE)) {
      viewer.attachWriter(writer);
      topic.handle(viewer, MessageParser.parse("TOPIC #c"));
      new JoinCommandHandler(registry, extensions, () -> "srv", () -> 50)
          .handle(viewer, MessageParser.parse("JOIN #c"));
    }
    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains(" 331 viewer #c :No topic is set\r\n")
        .doesNotContain(" 332 ", " 333 ");
  }

  private static ClientSession session(String nick) {
    ClientSession session = new ClientSession(nick, "host.example", RateLimitBucket.withDefaults());
    session.setNickname(nick);
    session.setIdent(nick);
    return session;
  }

  private static final class FixedCloak implements ServerExtension, HostnameDisplayExtension {
    @Override
    public String id() {
      return "fixed-cloak";
    }

    @Override
    public String extensionPoint() {
      return "hostname-display";
    }

    @Override
    public void start(ServerContext context) {
      // Stateless test extension: nothing to set up.
    }

    @Override
    public void stop() {
      // Stateless test extension: nothing to release.
    }

    @Override
    public String display(String hostname) {
      return "cloaked.example";
    }
  }
}
