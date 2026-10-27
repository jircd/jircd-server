# Contract: Userhost in NAMES

Source: [IRCv3 extension](https://ircv3.net/specs/extensions/userhost-in-names); builds on [capability SPI](../../014-capability-plugin-spi/contracts/capability-spi.md) and [budgets](../../014-capability-plugin-spi/contracts/reply-budgets.md).

## Negotiation and wire behavior

When enabled, CAP LS offers `userhost-in-names` without a value. Successful REQ ACKs before or after registration; LIST reflects negotiated state. Combined REQ remains atomic if any token is unavailable. Ordinary CAP removal restores default identity on new captures. No PROTOCTL command or UHNAMES ISUPPORT token is added.

For an operator who is also voiced:

| Negotiated | 353 member token |
| --- | --- |
| Neither | `@nick` |
| multi-prefix only | `@+nick` |
| userhost-in-names only | `@nick!ident@displayHost` |
| Both | `@+nick!ident@displayHost` |

No status means no prefix. Use the permitted display hostname, including a cloak where applicable; never the protected underlying hostname. This applies to explicit targeted/bare NAMES and JOIN/SAJOIN automatic lists. Existing membership visibility, numeric parameters, 366 and WHO remain unchanged.

## Provider interface and bounds

Register `CapabilityRegistrar.onNamesIdentity(bound, hook)` only. Hook consumes `MemberSnapshot`, not live sessions or registry data. Return nickname, ident and displayHost joined by `!`/`@`. Core centrally applies owner negotiation, validates the result and falls back on provider failure. No dependencies, resources, I/O or mutable lifecycle state are required.

The declared extra bytes are `I=U+H+2` from `ReplyLimits.identMax()/hostMax()`, using checked arithmetic. Current I=27+63+2=92. Keep this distinct from total identity bytes N+I.

Worst-case complete 353 member line: `S+2N+C+13+P+I<=512`; P=2 with installed multi-prefix. Closing 366: `S+N+C+29<=512`. All units are UTF-8 bytes including separators/CRLF. Preserve other independent reply bounds. Validate installed providers including disabled ones at startup/reload, and active composition on enable, without invoking member-render hooks. Reject unsafe configurations atomically. Pack complete composed tokens across lines; never truncate nick, ident or host.

## Configuration and lifecycle

```yaml
capabilities:
  multi-prefix: enabled
  userhost-in-names: enabled
```

The new id belongs only under `capabilities`, accepts `enabled`/`disabled`, and defaults to disabled on startup when omitted. Standard runtime controls and REHASH use the existing spec-014 lifecycle. Eligible clients receive NEW/DEL; disabling prunes negotiation and re-enabling requires explicit REQ. Captured output may follow DEL. Unknown/invalid configuration preserves previous state on reload.

Installation reserves larger bounds even when disabled. Document the resulting configuration compatibility change and actionable S/N/C reductions; do not weaken bounds to retain previously accepted unsafe combinations.
