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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.BanEntry;
import net.jircd.core.session.Channel;
import net.jircd.core.session.ChannelMode;
import net.jircd.core.session.ChannelModeChange;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ChannelVisibility;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.CoreChannelModes;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.PresentedIdentity;
import net.jircd.core.session.Recipients;
import net.jircd.core.session.SecurityEventLog;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import net.jircd.protocol.ModeChange;
import net.jircd.protocol.ModeStringParser;
import net.jircd.protocol.NickMask;
import net.jircd.protocol.NumericReply;

/**
 * {@code MODE} (channel form): a whole-command operator-privilege check up front (except the {@code
 * b}-alone bare ban-list query, which needs no privilege at all) answers 482 early, then each
 * change re-checks the requester's operator status under the channel monitor in the same step as
 * the mutation, so a requester de-opped or departed since the check changes nothing. It applies an
 * ordered {@link ModeChange} list left-to-right, stopping (but keeping what was already applied —
 * not atomic, not rolled back) at the first unrecognized flag, missing parameter, unresolvable
 * {@code MEMBER}-kind target, or change refused with 482 because the requester lost operator
 * status; a parameter-consuming flag beyond {@code ServerConfiguration.maxModesPerCommand} stops
 * silently, no reply beyond the echo of what was applied so far.
 */
public final class ModeCommandHandler implements CommandHandler {

  private final ChannelRegistry channelRegistry;
  private final NicknameRegistry nicknameRegistry;
  private final ExtensionRegistry extensionRegistry;
  private final Supplier<String> serverName;
  private final IntSupplier maxModesPerCommand;
  private final Runnable beforeChange;

  public ModeCommandHandler(
      ChannelRegistry channelRegistry,
      NicknameRegistry nicknameRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName,
      IntSupplier maxModesPerCommand) {
    this(
        channelRegistry,
        nicknameRegistry,
        extensionRegistry,
        serverName,
        maxModesPerCommand,
        () -> {});
  }

  // Test seam run after the whole-command check and before each mutation, to interleave a
  // de-op or departure of the requester; never invoked under a channel monitor.
  ModeCommandHandler(
      ChannelRegistry channelRegistry,
      NicknameRegistry nicknameRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName,
      IntSupplier maxModesPerCommand,
      Runnable beforeChange) {
    this.beforeChange = beforeChange;
    this.channelRegistry = channelRegistry;
    this.nicknameRegistry = nicknameRegistry;
    this.extensionRegistry = extensionRegistry;
    this.serverName = serverName;
    this.maxModesPerCommand = maxModesPerCommand;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    if (message.params().isEmpty()) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_NEEDMOREPARAMS,
          "MODE",
          "Not enough parameters");
      return;
    }
    String channelName = message.params().getFirst();
    var found = channelRegistry.lookup(channelName);
    if (found.isEmpty()) {
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
      // A bare "MODE #chan" is a read-only settings
      // query, not treated as an unrecognized flag anymore; runs before the operator-privilege
      // check below, the same structural position the bare ban-list query already occupies.
      sendChannelModeIs(session, channel);
      return;
    }

    String modeStringArg = message.params().get(1);
    String queryFlag = modeStringArg.startsWith("+") ? modeStringArg.substring(1) : modeStringArg;
    if (message.params().size() == 2 && queryFlag.length() == 1) {
      ChannelMode queryMode = resolve(CoreChannelModes.ALL, queryFlag.charAt(0));
      if (queryMode != null && queryMode.kind() == ChannelMode.Kind.LIST) {
        sendMaskList(session, channel, queryMode);
        return;
      }
    }

    if (!channel.operators().contains(session)) {
      refuse(session, channel);
      return;
    }

    applyChanges(session, channel, message);
  }

  private void refuse(ClientSession session, Channel channel) {
    SecurityEventLog.rejectedModerationAction(
        session.connectionId(), "MODE", channel.name(), "not a channel operator");
    Replies.send(
        session,
        serverName.get(),
        NumericReply.ERR_CHANOPRIVSNEEDED,
        channel.name(),
        "You're not channel operator");
  }

  private void applyChanges(ClientSession session, Channel channel, Message message) {
    // Only the modestring itself (e.g. "+ov-b") goes through ModeStringParser — the value
    // parameters that follow (nicknames, ban masks) are consumed directly from message.params()
    // below as each parameter-consuming flag is encountered, never re-scanned as mode letters.
    List<ModeChange> changes = ModeStringParser.parse(message.params().get(1));
    Collection<ChannelMode> recognized =
        extensionRegistry.recognizedChannelModes(CoreChannelModes.ALL);

    List<ModeChange> applied = new ArrayList<>();
    List<String> appliedParams = new ArrayList<>();
    int nextParamIndex = 2;
    int parameterConsumingCount = 0;

    for (ModeChange change : changes) {
      ChannelMode mode = resolve(recognized, change.flag());
      if (mode == null) {
        Replies.send(
            session,
            serverName.get(),
            NumericReply.ERR_UNKNOWNMODE,
            String.valueOf(change.flag()),
            "is unknown mode char to me");
        break;
      }

      if (mode.kind() == ChannelMode.Kind.BOOLEAN) {
        beforeChange.run();
        if (!applyOrRefuse(
            session, channel, new ChannelModeChange.Flag(mode, change.sign() == '+'))) {
          break;
        }
        applied.add(change);
        continue;
      }

      // Unsetting a VALUE_SET_ONLY mode (user-limit) takes no
      // parameter at all, the same no-parameter shape BOOLEAN unsetting already has; only setting
      // it consumes one, below.
      if (mode.kind() == ChannelMode.Kind.VALUE_SET_ONLY && change.sign() == '-') {
        beforeChange.run();
        if (!applyOrRefuse(session, channel, new ChannelModeChange.Limit(0))) {
          break;
        }
        applied.add(change);
        continue;
      }

      if (parameterConsumingCount >= maxModesPerCommand.getAsInt()) {
        break; // silent stop — no reply beyond the echo of what was applied so far
      }
      if (nextParamIndex >= message.params().size()) {
        Replies.send(
            session,
            serverName.get(),
            NumericReply.ERR_NEEDMOREPARAMS,
            "MODE",
            "Not enough parameters");
        break;
      }
      String rawParam = message.params().get(nextParamIndex++);
      parameterConsumingCount++;

      String appliedParam;
      if (mode.kind() == ChannelMode.Kind.MEMBER) {
        var target = channel.findMember(rawParam);
        if (target.isEmpty()) {
          // The same nickname-registry-existence check
          // WHOIS/KILL already do: 401 for "not connected anywhere", 441 only once it's
          // confirmed the nickname exists but isn't a member of THIS channel.
          if (nicknameRegistry.lookup(rawParam).isEmpty()) {
            Replies.send(
                session,
                serverName.get(),
                NumericReply.ERR_NOSUCHNICK,
                rawParam,
                "No such nick/channel");
          } else {
            Replies.send(
                session,
                serverName.get(),
                NumericReply.ERR_USERNOTINCHANNEL,
                rawParam,
                "They aren't on that channel");
          }
          break;
        }
        beforeChange.run();
        ChannelModeChange.Privilege privilege =
            new ChannelModeChange.Privilege(target.get(), privilegeOf(mode), change.sign() == '+');
        // A member's already-unset privilege is still a valid, echoable revoke.
        Channel.ModeChangeResult result = channel.applyAsOperator(session, privilege);
        if (result == Channel.ModeChangeResult.NOT_OPERATOR) {
          refuse(session, channel);
          break;
        }
        if (result == Channel.ModeChangeResult.NOT_A_MEMBER) {
          Replies.send(
              session,
              serverName.get(),
              NumericReply.ERR_USERNOTINCHANNEL,
              rawParam,
              "They aren't on that channel");
          break;
        }
        appliedParam = target.get().nickname();
      } else if (mode.kind() == ChannelMode.Kind.VALUE_SET_ONLY) {
        // +l <n>; -l never reaches here (handled above,
        // no parameter). An invalid (non-numeric or non-positive) value is silently ignored —
        // no crash, no error reply, the same "silently ignored" outcome irctest's own
        // testLimitInvalidValues explicitly accepts as valid server behavior.
        int limit;
        try {
          limit = Integer.parseInt(rawParam);
        } catch (NumberFormatException e) {
          break;
        }
        if (limit <= 0) {
          break;
        }
        beforeChange.run();
        if (!applyOrRefuse(session, channel, new ChannelModeChange.Limit(limit))) {
          break;
        }
        appliedParam = rawParam;
      } else if (mode.kind() == ChannelMode.Kind.VALUE_ALWAYS) {
        // +k/-k both always take a parameter; -k's value is
        // never checked against the current key (the same "don't require re-proving what you're
        // removing" precedent -b <mask> already sets). A +k value that's empty or contains a
        // space can never be supplied back via JOIN's own space-delimited key grammar (RFC 2812's
        // key grammar excludes both), so it's silently ignored rather than applied — the same
        // "silently ignored" outcome irctest's own testKeyValidation explicitly accepts, and the
        // only way to avoid setting a channel key nobody could ever join with again.
        if (change.sign() == '+' && (rawParam.isEmpty() || rawParam.indexOf(' ') >= 0)) {
          break;
        }
        beforeChange.run();
        String newKey = change.sign() == '+' ? rawParam : null;
        if (!applyOrRefuse(session, channel, new ChannelModeChange.Key(newKey))) {
          break;
        }
        appliedParam = rawParam;
      } else {
        // All mask lists share normalization, limits and operator checks.
        String normalized = NickMask.normalize(rawParam);
        beforeChange.run();
        if (change.sign() == '+') {
          BanEntry entry = new BanEntry(normalized, session.nickname(), Instant.now());
          Channel.ModeChangeResult result =
              channel.applyAsOperator(session, new ChannelModeChange.ListAdd(mode, entry));
          if (result == Channel.ModeChangeResult.NOT_OPERATOR) {
            refuse(session, channel);
            break;
          }
          if (result == Channel.ModeChangeResult.BAN_LIST_FULL) {
            Replies.send(
                session,
                serverName.get(),
                NumericReply.ERR_BANLISTFULL,
                channel.name(),
                normalized,
                "Channel list is full (+" + mode.flag() + ")");
            break;
          }
        } else if (!applyOrRefuse(
            session, channel, new ChannelModeChange.ListRemove(mode, normalized))) {
          break;
        }
        appliedParam = normalized;
      }
      applied.add(change);
      appliedParams.add(appliedParam);
    }

    if (!applied.isEmpty()) {
      broadcastEcho(session, channel, applied, appliedParams);
    }
  }

  // Applies a change that can only be refused for lack of operator status; true if applied.
  private boolean applyOrRefuse(ClientSession session, Channel channel, ChannelModeChange change) {
    if (channel.applyAsOperator(session, change) == Channel.ModeChangeResult.NOT_OPERATOR) {
      refuse(session, channel);
      return false;
    }
    return true;
  }

  private static Channel.MemberPrivilege privilegeOf(ChannelMode mode) {
    return CoreChannelModes.OPERATOR.equals(mode)
        ? Channel.MemberPrivilege.OPERATOR
        : Channel.MemberPrivilege.VOICE;
  }

  private static ChannelMode resolve(Collection<ChannelMode> recognized, char flag) {
    for (ChannelMode mode : recognized) {
      if (mode.flag() == flag) {
        return mode;
      }
    }
    return null;
  }

  /**
   * {@code 324 RPL_CHANNELMODEIS} — every currently active {@code BOOLEAN}/{@code
   * VALUE_SET_ONLY}/{@code VALUE_ALWAYS}-kind flag as one mode string, each value-carrying flag's
   * current value appended as a trailing param in the same order its letter appears. {@code
   * LIST}/{@code MEMBER}-kind flags are deliberately excluded — {@code b} (ban masks) and {@code
   * o}/{@code v} (per-member privileges) already have their own dedicated query forms ({@code MODE
   * #chan +b}, {@code NAMES}'s {@code @}/{@code +} prefixes) and are not duplicated here
   * (research.md "Mode string composition and scope boundary"). No operator privilege is required —
   * this is a read-only query, mirroring {@code TOPIC}'s own view-vs-set split; a private/secret
   * channel is hidden from a non-member the same way {@code TOPIC}/{@code NAMES} already hide
   * theirs.
   */
  private void sendChannelModeIs(ClientSession session, Channel channel) {
    if (ChannelVisibility.isHiddenFrom(channel, session, extensionRegistry)) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_NOSUCHCHANNEL,
          channel.name(),
          "No such channel");
      return;
    }
    Collection<ChannelMode> recognized =
        extensionRegistry.recognizedChannelModes(CoreChannelModes.ALL);
    StringBuilder letters = new StringBuilder("+");
    List<String> values = new ArrayList<>();
    for (ChannelMode mode : recognized) {
      if (mode.kind() == ChannelMode.Kind.LIST || mode.kind() == ChannelMode.Kind.MEMBER) {
        continue;
      }
      if (mode.kind() == ChannelMode.Kind.BOOLEAN) {
        if (channel.activeModes().contains(mode)) {
          letters.append(mode.flag());
        }
      } else if (mode.kind() == ChannelMode.Kind.VALUE_SET_ONLY) {
        if (channel.memberLimit() > 0) {
          letters.append(mode.flag());
          values.add(String.valueOf(channel.memberLimit()));
        }
      } else if (mode.kind() == ChannelMode.Kind.VALUE_ALWAYS && channel.key() != null) {
        letters.append(mode.flag());
        values.add(channel.key());
      }
    }
    List<String> params = new ArrayList<>();
    params.add(channel.name());
    params.add(letters.toString());
    params.addAll(values);
    Replies.send(
        session, serverName.get(), NumericReply.RPL_CHANNELMODEIS, params.toArray(new String[0]));
    Replies.send(
        session,
        serverName.get(),
        NumericReply.RPL_CHANNELCREATED,
        channel.name(),
        String.valueOf(channel.createdAt().getEpochSecond()));
  }

  private void sendMaskList(ClientSession session, Channel channel, ChannelMode mode) {
    NumericReply entryReply =
        switch (mode.flag()) {
          case 'e' -> NumericReply.RPL_EXCEPTLIST;
          case 'I' -> NumericReply.RPL_INVITELIST;
          default -> NumericReply.RPL_BANLIST;
        };
    NumericReply endReply =
        switch (mode.flag()) {
          case 'e' -> NumericReply.RPL_ENDOFEXCEPTLIST;
          case 'I' -> NumericReply.RPL_ENDOFINVITELIST;
          default -> NumericReply.RPL_ENDOFBANLIST;
        };
    for (BanEntry ban : channel.masks(mode)) {
      Replies.send(
          session,
          serverName.get(),
          entryReply,
          channel.name(),
          ban.mask(),
          ban.setBy(),
          String.valueOf(ban.setAt().getEpochSecond()));
    }
    String endText =
        switch (mode.flag()) {
          case 'e' -> "End of channel exception list";
          case 'I' -> "End of channel invite list";
          default -> "End of channel ban list";
        };
    Replies.send(session, serverName.get(), endReply, channel.name(), endText);
  }

  private void broadcastEcho(
      ClientSession session,
      Channel channel,
      List<ModeChange> applied,
      List<String> appliedParams) {
    List<String> params = new ArrayList<>();
    params.add(channel.name());
    params.add(ModeEcho.format(applied));
    params.addAll(appliedParams);
    String presentedForm = PresentedIdentity.presentedForm(session, extensionRegistry);
    Message echo = new Message(Map.of(), presentedForm, Command.MODE, "MODE", params);
    Recipients.deliver(channel.members(), echo);
  }
}
