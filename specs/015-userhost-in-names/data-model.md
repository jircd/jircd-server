# Data Model: Userhost in NAMES

No database, new mutable domain state or persistence.

| Entity | Fields / relationships | Constraints |
| --- | --- | --- |
| Provider registration | id/name `userhost-in-names`; identity hook; bound | Exactly one capability; identity slot only; no multi-prefix dependency |
| Existing MemberSnapshot | nickname, ident, displayHost, heldStatuses | All identity strings non-null; heldStatuses immutable and PREFIX-ordered; displayHost already permitted for requester |
| Existing ReplyLimits | serverBytes, nicknameMax, channelMax, identMax, hostMax | Nonnegative byte limits; additional identity bound is `I=U+H+2` with checked arithmetic |
| Derived identity token | nickname + `!` + ident + `@` + displayHost | Nonempty; no whitespace or control characters; UTF-8 bytes <= nickname bytes + I |
| Complete NAMES token | independently composed status + identity | No truncation within token; status follows PREFIX order |
| NAMES reply list | visible complete member tokens, 353 lines, 366 | Command section including CRLF <=512 bytes; split only between complete tokens |

Provider has no owned session flags: existing negotiated capability state controls the hook. Discovered → enabled → offered; CAP ACK → negotiated; CAP removal or provider withdrawal → default identity for new captures. Re-enable offers capability but does not restore negotiation. Older captured output remains valid under spec-014 lifecycle rules.

Registration failures and malformed/over-bound results use the existing SPI's FAILED withdrawal and nickname fallback. Do not introduce a custom failure state or recovery loop. Installing even a disabled valid descriptor affects conservative reply bounds; failed configuration validation publishes no new state.
