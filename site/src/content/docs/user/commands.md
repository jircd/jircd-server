---
title: IRC commands
description: Commands implemented by jircd and their common numeric replies.
sidebar:
  order: 3
---

Commands are sent as IRC lines. Parameters in angle brackets are placeholders; a leading colon marks the trailing parameter. Numeric replies are server responses, not commands. Exact replies depend on the request and server state.

| Command | Purpose and common numeric replies |
| --- | --- |
| `ADMIN` | Request server administrator details (`RPL_ADMINME` 256, `RPL_ADMINLOC1` 257, `RPL_ADMINLOC2` 258, `RPL_ADMINEMAIL` 259). |
| `AWAY` | Set or clear your away message; replies with `RPL_NOWAWAY` (306) or `RPL_UNAWAY` (305). |
| `CAP` | List, request, enable or end capability negotiation. See [Capabilities](capabilities.md). |
| `HELP` | Request help for a command or topic. `HELPOP` is accepted as an alias. |
| `INFO` | Request server information (`RPL_INFO` 371, `RPL_ENDOFINFO` 374). |
| `INVITE` | Invite a user to a channel (`RPL_INVITING` 341); the target receives `INVITE`. |
| `ISON` | Check which requested nicknames are online (`RPL_ISON` 303). |
| `JOIN` | Join one or more channels. |
| `KICK` | Remove a channel member (requires channel operator status). |
| `LIST` | List visible channels (`RPL_LIST` 322, `RPL_LISTEND` 323). |
| `LUSERS` | Show user and channel counts (replies 251–255 and 265–266). |
| `NAMES` | List visible channel members (`RPL_NAMREPLY` 353, `RPL_ENDOFNAMES` 366). |
| `OPER`, `EXTENSION`, `REHASH`, `WHOHOST`, `SAJOIN`, `SAMODE`, `KILL`, `WALLOPS` | Optional administrative commands, provided by the `admin` extension. |
| `TAGMSG` | Send a tag-only message; provided by `message-tags`. |
| `NICK` | Set or change your nickname. |
| `NOTICE` | Send a notice to a user or channel; notices do not generate automatic error replies. |
| `PART` | Leave one or more channels. |
| `PASS` | Accepted before registration and ignored: the server has no connection password. After registration it returns `ERR_ALREADYREGISTRED` (462). |
| `PING` | Check that the server responds; the server answers with `PONG`. |
| `PONG` | Reply to a server ping. |
| `PRIVMSG` | Send a message to a user or channel. |
| `QUIT` | Disconnect with an optional reason. |
| `STATS` | Request server statistics. Only `STATS u` reports data (`RPL_STATSUPTIME` 242); every other letter returns just `RPL_ENDOFSTATS` (219). |
| `TIME` | Request server time (`RPL_TIME` 391). |
| `TOPIC` | View or change a channel topic (`RPL_TOPIC` 332 and `RPL_TOPICWHOTIME` 333 when set). |
| `USER` | Provide the user name and real name during registration. |
| `USERHOST` | Request user/host status for nicknames (`RPL_USERHOST` 302). |
| `VERSION` | Request server version (`RPL_VERSION` 351). |
| `WHO` | Search users (`RPL_WHOREPLY` 352, `RPL_ENDOFWHO` 315). |
| `WHOIS` | Request information about a user (replies 311–319 as applicable). |
| `WHOWAS` | Request remembered information about a former nickname (`RPL_WHOWASUSER` 314, `RPL_ENDOFWHOWAS` 369). |
| `MODE` | Inspect or change user and channel modes. See [Channel modes](channel-modes.md). |
| `MOTD` | Request the message of the day (`RPL_MOTD` 372, `RPL_ENDOFMOTD` 376; `ERR_NOMOTD` 422 if unset). |

The optional `admin` server extension adds `OPER`, `EXTENSION`, `REHASH`, `WHOHOST`, `SAJOIN`, `SAMODE`, `KILL`, and `WALLOPS`. These administrative commands require the extension and, where applicable, operator authorization. The optional `message-tags` capability adds `TAGMSG`.

Errors use standard numeric replies, including `ERR_NOSUCHNICK` (401), `ERR_NOSUCHCHANNEL` (403), `ERR_NOTREGISTERED` (451), `ERR_NEEDMOREPARAMS` (461), and `ERR_UNKNOWNCOMMAND` (421). Consult the relevant command's response when diagnosing a rejected request.
