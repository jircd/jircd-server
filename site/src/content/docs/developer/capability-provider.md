---
title: Capability providers
description: Implement and operate an IRCv3 capability provider through JIRCD's SPI.
sidebar:
  order: 3
---

A capability module implements [`CapabilityExtension`](/api/net/jircd/core/extension/CapabilityExtension.html). Override `register` to stage its hooks through [`CapabilityRegistrar`](/api/net/jircd/core/extension/capability/CapabilityRegistrar.html). Core publishes registrations only after the descriptor validates; failed enablement publishes nothing. The bundled providers are separate Gradle modules, so removing one removes both its capability offer and behavior.

| Registration | Purpose | Bundled use |
| --- | --- | --- |
| `onOutboundTags(keys, maxBytes, hook)` | Supplies owned server tags to recipients that negotiated the capability, within the declared escaped-byte bound. | `server-time` (`time`), `message-tags` (`msgid`) |
| `onInboundTags` | Selects client-only (`+`) tags to forward and provides provider-scoped request context. | `message-tags` |
| `onRecipients` | Adds a `SENDER_COPY` role to permitted `PRIVMSG`, `NOTICE`, or `TAGMSG` delivery; core merges roles to avoid duplicate delivery. | `echo-message` |
| `onEvent(kind, hook)` | Handles committed `AWAY_CHANGED`, `JOINED`, `PARTED`, `KICKED`, `QUIT`, or `NICK_CHANGED` events for peers in the event audience. | `away-notify` |
| `onMemberStatus`, `onNamesIdentity`, `onReplyField` | Adds bounded decorations to declared reply slots, such as NAMES status or identity fields. | `multi-prefix`, `userhost-in-names` |
| `onOutputGroup(prerequisites, hook)` | Frames a finite group; the complete group is queued together or sent plain if negotiation requirements are absent. | `batch` |
| `command` | Registers a provider-owned command in the published snapshot. It cannot replace a core or server-extension command. | `message-tags` (`TAGMSG`) |

Hooks must be pure, nonblocking, and thread-safe. Treat results as untrusted: an exception, `null`, or out-of-bounds result is dropped and replaced by its documented default. Core state and other providers remain unaffected. A fault withdraws the provider as `FAILED`, along with hard dependents, in the same publication used for disable and emits one `CAP DEL`. The provider remains failed until an administrator explicitly retries it with `EXTENSION ENABLE`, or a `REHASH`/`SIGHUP` changes that provider's `capabilities` entry. Changes to unrelated configuration do not retry it. Logs identify provider, hook kind, and category without exception text or client content; repeated failures are throttled per provider. A valid tag contribution dropped only because the combined size exceeds the limit does not fail a provider. Core applies enabled state and negotiation prerequisites before invoking hooks.

## Runtime changes

`EXTENSION`, `REHASH`, and `SIGHUP` use one capability lifecycle. Under the administrative mutex, disabling publishes a new snapshot and removes the provider and its hard dependents from connected sessions. Clients that sent `CAP LS 302` or negotiated `cap-notify` receive `CAP DEL`; enabling sends `CAP NEW` but does not restore client negotiation. Core advertises `cap-notify` even when no provider is installed. Work prepared before a change may still be delivered afterward; newly prepared work sees the new state. Output groups choose framed or plain form at queue admission, preserving each group's closing frame. Provider `start` and `stop` run outside the mutex and must allow callbacks already in flight to overlap.

## API changes

The following migration notes describe the current API. Linked names have pages in the generated `/api/` reference; names without links are not included in that Javadoc source set.

| Removed API | Current API |
| --- | --- |
| `CapabilityExtension.contributeTags` | [`CapabilityRegistrar.onOutboundTags`](/api/net/jircd/core/extension/capability/CapabilityRegistrar.html) |
| `CapabilityExtension.includeSenderInFanOut` | [`CapabilityRegistrar.onRecipients`](/api/net/jircd/core/extension/capability/CapabilityRegistrar.html) returning [`DeliveryRole.SENDER_COPY`](/api/net/jircd/core/extension/capability/DeliveryRole.html) |
| `Channel.memberListing(boolean)`, `Channel.statusPrefixes(member, boolean)` | [`Channel.memberFacts()`](/api/net/jircd/core/session/Channel.html) and `heldStatuses(member)` return unformatted facts; `MemberReplies` renders for a requester. `memberListing()` is the highest-status form. |
| `JoinResult.memberListing()` | [`JoinResult.members()`](/api/net/jircd/core/session/JoinResult.html) returns unformatted `MemberFacts`. |
| `JoinCommandHandler.sendNamesReply` and `sendNamesLine` overloads without a registry | Use the overloads that take `ExtensionRegistry` first. The `sendNamesReply(..., List<String> tokens)` overload still sends rendered tokens. |
| `SessionWriter.enqueueBatch` | [`SessionWriter.enqueueGroup`](/api/net/jircd/core/session/SessionWriter.html); framing comes from the owning provider. |
| `MessageCommandHandler.resolveRecipients`, `deliveryTargets`, `includeSenderInFanOut` | `MessageDelivery.authorize` and `deliver`. |
| Core `TagmsgCommandHandler` | The command belongs to the `message-tags` module. |
| `ReplyBudgetCatalog.legacyCompatible()` | [`ReplyBudgetCatalog.empty()`](/api/net/jircd/core/extension/capability/ReplyBudgetCatalog.html) and provider-declared bounds. |
| Mutating `ClientSession.negotiatedCapabilities()` | [`ClientSession.negotiatedCapabilities()`](/api/net/jircd/core/session/ClientSession.html) is an immutable snapshot; replace the set with `replaceNegotiatedCapabilities`. |
| `CapabilityNegotiator.isAcceptable`, `apply` | `candidate` validates without changing state; `request` validates and applies under the administrative mutex. |
| `CapabilityNegotiationGrammar.ls`, `list` | `lsReplies`, `listReplies` (302 continuation lines), and `notifications` for `CAP NEW`/`DEL`. |
| Provider commands looked up in [`CommandRegistrar`](/api/net/jircd/core/extension/CommandRegistrar.html)'s core table | [`ExtensionRegistry.capabilityCommand`](/api/net/jircd/core/extension/ExtensionRegistry.html)([`Command`](/api/net/jircd/protocol/Command.html)); dispatch checks core's table first. |
| `HookDispatcher.guardedCommand(registration, live, refused)` | [`HookDispatcher.guardedCommand(registration)`](/api/net/jircd/core/extension/capability/HookDispatcher.html) wraps hook faults. |
| `CapabilityRuntime.withdraw` returning a snapshot; `ExtensionRegistry.disable` refusing while dependents are enabled | `withdraw` returns withdrawn provider IDs; disabling a prerequisite withdraws its hard dependents in the same step. |
| [`FaultReporter`](/api/net/jircd/core/extension/capability/FaultReporter.html) `.logging()` | The registry reports and withdraws a faulting provider. [`ExtensionRegistry(Executor)`](/api/net/jircd/core/extension/ExtensionRegistry.html) chooses where withdrawals run; a custom `HookDispatcher` accepts a `FaultReporter`. |
| Six-component `ExtensionRegistry.DesiredState` | It has a seventh `changedCapabilities` component. Only entries changed since the last applied configuration may retry a failed provider; the six-argument constructor remains and treats every listed entry as changed. |
| Aggregate-overflow tag drop reported as `FaultCategory.INVALID_RESULT` | It is reported as [`FaultCategory.COMPOSITION_LIMIT`](/api/net/jircd/core/extension/capability/FaultCategory.html), which `providerFault()` identifies as no provider's fault. |

Reply decorations use [`ReplyLayout`](/api/net/jircd/core/extension/capability/ReplyLayout.html) budgets rather than per-capability widths. Startup, rehash, and runtime enable reserve the declared slot bytes of installed providers using the same arithmetic. `multi-prefix` reserves the two operator and voice symbols from `PREFIX=(ov)@+`; without it, core reserves only its one-byte highest status. `userhost-in-names` contributes `U+H+2` identity bytes independently, composing with status as `@+nick!ident@displayHost`. Its hook uses the permitted display host, including cloak output, never a protected real hostname. Enable it with `capabilities: {userhost-in-names: enabled}`; omitted means disabled. It affects targeted/bare NAMES and automatic JOIN/SAJOIN listings only after client negotiation, before or after registration. WHO and ordinary clients retain their existing behavior. Disabling prunes negotiation through the normal lifecycle; re-enabling requires a fresh `CAP REQ`. These runtime rules are implemented by [`CapabilityRuntime`](/api/net/jircd/core/extension/capability/CapabilityRuntime.html).
