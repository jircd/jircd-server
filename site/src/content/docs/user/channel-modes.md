---
title: Channel modes
description: Channel mode letters supported by jircd and the parameter types they use.
sidebar:
  order: 5
---

Use `MODE #channel` to inspect channel modes. Only a channel operator can change them. Mode letters are grouped by how they use parameters:

| Mode | Meaning | Parameter |
| --- | --- | --- |
| `b` | Add, remove or list ban masks. Matching users cannot join or send to the channel. | Mask when adding or removing; no parameter to list. |
| `e` | Add, remove or list ban exceptions. | Mask when adding or removing. |
| `I` | Add, remove or list invite exceptions. | Mask when adding or removing. |
| `i` | Invite-only channel. | None. |
| `k` | Channel key required to join. | Key when setting or unsetting. |
| `l` | Maximum number of channel members. | Limit when setting; none when unsetting. |
| `m` | Moderated channel: restricts who may speak. | None. |
| `n` | Restrict channel messages to members. | None. |
| `o` | Grant or remove channel operator status for a member. | Nickname. |
| `p` | Private channel visibility. | None. |
| `s` | Secret channel visibility. | None. |
| `t` | Restrict topic changes to channel operators. | None. |
| `v` | Grant or remove voice status for a member. | Nickname. |

`p` and `s` are mutually exclusive. List modes `b`, `e`, and `I` can be queried without a mode change. The server advertises the supported mode catalog in its registration features. Unsupported mode letters are rejected.

Mode changes use `MODE #channel +letters` or `MODE #channel -letters`, followed by parameters for the selected letters. The server checks each change against channel membership, operator permissions, and parameter requirements. A rejected change does not grant the requested privilege. See [Commands](commands.md) for other commands.
