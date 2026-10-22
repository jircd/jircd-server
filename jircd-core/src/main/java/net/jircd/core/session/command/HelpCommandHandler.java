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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code HELP} and {@code HELPOP} — a short built-in topic list. The first line of a topic goes in
 * {@code 704}, the rest in {@code 705}, closed by {@code 706}; the subject is the upper-cased topic
 * ({@code index} when none was asked for). An unknown topic is {@code 524}.
 */
public final class HelpCommandHandler implements CommandHandler {

  private static final String INDEX = "index";

  private static final Map<String, List<String>> TOPICS = topics();

  private final Supplier<String> serverName;

  public HelpCommandHandler(Supplier<String> serverName) {
    this.serverName = serverName;
  }

  private static Map<String, List<String>> topics() {
    Map<String, List<String>> topics = new java.util.LinkedHashMap<>();
    topics.put(
        "JOIN",
        List.of(
            "JOIN <channel>[,<channel>...] [<key>[,<key>...]]",
            "Joins the channels, creating one that does not exist yet."));
    topics.put(
        "PART", List.of("PART <channel>[,<channel>...] [<message>]", "Leaves the channels."));
    topics.put("NICK", List.of("NICK <nickname>", "Changes your nickname, if no one else has it."));
    topics.put(
        "PRIVMSG",
        List.of(
            "PRIVMSG <target>[,<target>...] :<text>",
            "Sends text to a nickname or channel. NOTICE is the same without automatic replies."));
    topics.put(
        "TOPIC",
        List.of("TOPIC <channel> [:<topic>]", "Shows a channel's topic, or sets it if allowed."));
    topics.put(
        "MODE",
        List.of(
            "MODE <nickname> [<modes>] | MODE <channel> [<modes> [<parameters>]]",
            "Shows or changes user modes, or channel modes if you are a channel operator."));
    topics.put(
        "WHOIS", List.of("WHOIS <nickname>", "Shows who a nickname is and what it has joined."));
    topics.put(
        "ISON",
        List.of(
            "ISON <nickname> [<nickname>...]",
            "Replies with those of the nicknames that are online."));
    topics.put("MOTD", List.of("MOTD", "Shows the server's message of the day, if it has one."));
    topics.put("ADMIN", List.of("ADMIN", "Shows who to contact about this server."));
    topics.put(
        "STATS",
        List.of(
            "STATS <letter>",
            "STATS u shows how long the server has been up. Other letters report nothing."));
    topics.put("HELP", List.of("HELP [<topic>]", "Shows this help; HELPOP is the same."));
    return Map.copyOf(topics);
  }

  @Override
  public void handle(ClientSession session, Message message) {
    String server = serverName.get();
    String requested = message.params().isEmpty() ? "" : message.params().getFirst().strip();
    if (requested.startsWith("/")) {
      requested = requested.substring(1);
    }
    if (requested.isEmpty() || requested.equals("?") || requested.equalsIgnoreCase(INDEX)) {
      send(session, server, INDEX, indexLines());
      return;
    }
    List<String> lines = TOPICS.get(requested.toUpperCase(Locale.ROOT));
    if (lines == null) {
      Replies.sendEchoing(
          session, server, NumericReply.ERR_HELPNOTFOUND, requested, "Help not found");
      return;
    }
    send(session, server, requested.toUpperCase(Locale.ROOT), lines);
  }

  private static List<String> indexLines() {
    String names = String.join(" ", TOPICS.keySet().stream().sorted().toList());
    return List.of("Help topics (use HELP <topic>):", names);
  }

  private static void send(
      ClientSession session, String server, String subject, List<String> lines) {
    Replies.sendFitted(
        session, server, NumericReply.RPL_HELPSTART, List.of(subject), lines.getFirst());
    for (String line : lines.subList(1, lines.size())) {
      Replies.sendFitted(session, server, NumericReply.RPL_HELPTXT, List.of(subject), line);
    }
    Replies.send(session, server, NumericReply.RPL_ENDOFHELP, subject, "End of /HELP");
  }
}
