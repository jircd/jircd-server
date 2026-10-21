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
package net.jircd.core.extension;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.jircd.core.session.Channel;
import net.jircd.core.session.ChannelMode;
import net.jircd.core.session.CoreChannelModes;

/**
 * The server-scoped {@code RPL_ISUPPORT} advertisement (FR-055) — a computed snapshot, not stored
 * state. Fixed tokens ({@code CASEMAPPING}, {@code CHANTYPES}, {@code UTF8ONLY}) are constants;
 * {@code CHANMODES}/ {@code PREFIX} are recomputed whenever the recognized {@link ChannelMode}
 * catalog changes; the four length-derived tokens are recomputed whenever {@code
 * ServerConfiguration} is (re)loaded (FR-056/FR-064) — never on a per-registration basis.
 */
public record SupportedFeatures(
    int nicknameMaxLength,
    int channelNameMaxLength,
    int topicMaxLength,
    int maxModesPerCommand,
    String chanModes,
    String prefix) {

  public static SupportedFeatures defaults() {
    return of(9, 50, 390, 6, CoreChannelModes.ALL);
  }

  public static SupportedFeatures of(
      int nicknameMaxLength,
      int channelNameMaxLength,
      int topicMaxLength,
      int maxModesPerCommand,
      java.util.Collection<ChannelMode> recognizedChannelModes) {
    return new SupportedFeatures(
        nicknameMaxLength,
        channelNameMaxLength,
        topicMaxLength,
        maxModesPerCommand,
        formatChanModes(recognizedChannelModes),
        formatPrefix(recognizedChannelModes));
  }

  public SupportedFeatures withConfiguredLengths(
      int nicknameMaxLength, int channelNameMaxLength, int topicMaxLength, int maxModesPerCommand) {
    return new SupportedFeatures(
        nicknameMaxLength,
        channelNameMaxLength,
        topicMaxLength,
        maxModesPerCommand,
        chanModes,
        prefix);
  }

  private static String formatChanModes(java.util.Collection<ChannelMode> modes) {
    String list = letters(modes, ChannelMode.Kind.LIST);
    // 006-complete-core-protocol FR-004/FR-001 — channel-key (k) and user-limit (l) fill these
    // two groups, previously always empty.
    String alwaysParam = letters(modes, ChannelMode.Kind.VALUE_ALWAYS);
    String setOnlyParam = letters(modes, ChannelMode.Kind.VALUE_SET_ONLY);
    String bool = letters(modes, ChannelMode.Kind.BOOLEAN);
    return list + "," + alwaysParam + "," + setOnlyParam + "," + bool;
  }

  private static String letters(java.util.Collection<ChannelMode> modes, ChannelMode.Kind kind) {
    return modes.stream()
        .filter(m -> m.kind() == kind)
        .map(ChannelMode::flag)
        .sorted()
        .map(String::valueOf)
        .reduce("", String::concat);
  }

  private static String formatPrefix(java.util.Collection<ChannelMode> modes) {
    // operator (@) before voice (+), the standard IRC PREFIX ordering.
    List<ChannelMode> memberModes =
        new ArrayList<>(modes.stream().filter(m -> m.kind() == ChannelMode.Kind.MEMBER).toList());
    memberModes.sort(Comparator.comparing(m -> "operator".equals(m.id()) ? 0 : 1));
    StringBuilder letters = new StringBuilder();
    StringBuilder symbols = new StringBuilder();
    for (ChannelMode mode : memberModes) {
      letters.append(mode.flag());
      symbols.append("operator".equals(mode.id()) ? '@' : '+');
    }
    return "(" + letters + ")" + symbols;
  }

  public List<String> tokens() {
    return List.of(
        "CASEMAPPING=rfc1459",
        "CHANTYPES=#",
        "NICKLEN=" + nicknameMaxLength,
        "CHANNELLEN=" + channelNameMaxLength,
        "TOPICLEN=" + topicMaxLength,
        "MODES=" + maxModesPerCommand,
        "CHANMODES=" + chanModes,
        "PREFIX=" + prefix,
        "EXCEPTS=e",
        "INVEX=I",
        "MAXLIST=" + letters(CoreChannelModes.ALL, ChannelMode.Kind.LIST) + ":" + Channel.MAX_BANS,
        "UTF8ONLY");
  }
}
