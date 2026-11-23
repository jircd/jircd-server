---
title: Connect to jircd
description: Configure a client and make your first connection to a jircd server.
sidebar:
  order: 1
---

## Before you connect

Ask the server administrator for the server name or address and a listener port. A default empty configuration listens on port `6667` without TLS. TLS is available only when the administrator configures a TLS listener; see [TLS](tls.md). Administrators can review [listener configuration](../admin/configuration.md#listeners) and [connection limits](../admin/connection-limits.md) when setting up a public server.

## Make your first connection

In your IRC client, enter the server address and port, choose a nickname, and connect. For a local server started with the default settings, connect to `localhost` on port `6667`.

A minimal client conversation is:

```text
NICK alice
USER alice 0 * :Alice
JOIN #lobby
PRIVMSG #lobby :Hello!
```

`NICK` chooses your nickname. `USER` supplies the user name and real name required to finish registration. Once connected, join a channel with `JOIN` and send a channel message with `PRIVMSG`. You can also send a private message to a nickname with `PRIVMSG`.

The server supports capability negotiation. Clients may begin with `CAP LS`, request supported capabilities with `CAP REQ`, and finish negotiation with `CAP END`. See [Capabilities](capabilities.md) for the available options. A client that does not negotiate optional capabilities can use the core IRC commands and channel features.

For more, see [Commands](commands.md), [Channel modes](channel-modes.md), and [TLS](tls.md).
