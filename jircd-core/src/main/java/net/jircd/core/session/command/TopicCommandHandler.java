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
package net.jircd.core.session.command;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.Channel;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ChannelVisibility;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.CoreChannelModes;
import net.jircd.core.session.PresentedIdentity;
import net.jircd.core.session.Recipients;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code TOPIC} — view (any client) or set a channel's topic; setting requires operator privilege
 * only while {@code topic-lock} is active — any member may set it otherwise. A {@code
 * private}/{@code secret} channel is invisible to a non-member, non-administrator requester,
 * indistinguishable from a nonexistent one.
 */
public final class TopicCommandHandler implements CommandHandler {

  private final ChannelRegistry channelRegistry;
  private final ExtensionRegistry extensionRegistry;
  private final Supplier<String> serverName;
  private final IntSupplier topicMaxLength;
  private final Clock clock;

  public TopicCommandHandler(
      ChannelRegistry channelRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName,
      IntSupplier topicMaxLength) {
    this(channelRegistry, extensionRegistry, serverName, topicMaxLength, Clock.systemUTC());
  }

  public TopicCommandHandler(
      ChannelRegistry channelRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName,
      IntSupplier topicMaxLength,
      Clock clock) {
    this.clock = clock;
    this.channelRegistry = channelRegistry;
    this.extensionRegistry = extensionRegistry;
    this.serverName = serverName;
    this.topicMaxLength = topicMaxLength;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    if (message.params().isEmpty()) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_NEEDMOREPARAMS,
          "TOPIC",
          "Not enough parameters");
      return;
    }
    String channelName = message.params().getFirst();
    var found = channelRegistry.lookup(channelName);
    if (found.isEmpty()
        || ChannelVisibility.isHiddenFrom(found.get(), session, extensionRegistry)) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_NOSUCHCHANNEL,
          channelName,
          "No such channel");
      return;
    }
    Channel channel = found.get();

    if (message.params().size() < 2) {
      Channel.Topic topic = channel.topicState();
      if (topic == null) {
        Replies.send(
            session, serverName.get(), NumericReply.RPL_NOTOPIC, channel.name(), "No topic is set");
      } else {
        Replies.sendFitted(
            session,
            serverName.get(),
            NumericReply.RPL_TOPIC,
            List.of(channel.name()),
            topic.text());
        Replies.send(
            session,
            serverName.get(),
            NumericReply.RPL_TOPICWHOTIME,
            channel.name(),
            topic.setter(),
            Long.toString(topic.setAt()));
      }
      return;
    }

    // Operator privilege is only required while
    // topic-lock is active; any member may set the topic when it's off.
    if (channel.activeModes().contains(CoreChannelModes.TOPIC_LOCK)
        && !channel.operators().contains(session)) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_CHANOPRIVSNEEDED,
          channel.name(),
          "You're not channel operator");
      return;
    }

    String newTopic = message.params().get(1);
    if (newTopic.length() > topicMaxLength.getAsInt()) {
      Replies.send(session, serverName.get(), NumericReply.ERR_INPUTTOOLONG, "Topic too long");
      return;
    }

    channel.setTopic(
        newTopic,
        PresentedIdentity.presentedForm(session, extensionRegistry),
        clock.instant().getEpochSecond());
    Message topicNotification =
        new Message(
            Map.of(), serverName.get(), Command.TOPIC, "TOPIC", List.of(channel.name(), newTopic));
    Recipients.deliver(channel.members(), topicNotification);
  }
}
