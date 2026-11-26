---
title: IRCv3 capabilities
description: Discover and negotiate optional IRCv3 capabilities supported by jircd.
sidebar:
  order: 4
---

Clients negotiate capabilities with `CAP` during connection registration. Send `CAP LS` to discover offers, `CAP REQ :name` to request one or more, and `CAP END` to finish. The server answers with `CAP ACK` or `CAP NAK` to report the result. Capability names are not enabled for a client until negotiation succeeds.

jircd has core negotiation support, including `cap-notify`. The following bundled capabilities are optional and disabled unless enabled in the server configuration:

| Capability | What the client receives |
| --- | --- |
| `message-tags` | Message tags on supported messages; also provides the `TAGMSG` command. |
| `server-time` | Server timestamp tags on supported messages. |
| `echo-message` | An echo of messages you send. |
| `away-notify` | Notifications when another user changes away status. |
| `batch` | Batch framing for supported grouped replies and events. |
| `multi-prefix` | All applicable membership prefixes in names and membership replies. |
| `userhost-in-names` | User and host information alongside names in `NAMES` replies. |

An administrator enables a bundled capability in the `capabilities` configuration section, for example:

```yaml
capabilities:
  server-time: enabled
  message-tags: enabled
```

Omitted capability entries are disabled. Availability also depends on the capability module being present in the server runtime. If capabilities change while the server is running, clients that support notifications may receive `CAP NEW` or `CAP DEL`; a newly enabled capability is not automatically negotiated for existing clients. See the [container sample configuration](https://github.com/jircd/jircd-server/blob/main/deploy/container/jircd.yaml).
