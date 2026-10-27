# Research: Userhost in NAMES

All technical unknowns resolved for planning; no user clarification needed.

## 1. Existing identity SPI

**Decision**: Add one provider registering `onNamesIdentity`; use a new protocol name constant.

**Rationale**: `CapabilityRegistrar`, `Hooks.NamesIdentityHook`, `MemberSnapshot`, `HookDispatcher.namesIdentity` and `MemberReplies.namesTokens` already implement generic capture, negotiation, validation, failure fallback and permitted identity inputs. Multi-prefix demonstrates module/build/service registration. CapabilityName currently has no userhost constant.

**Alternatives considered**: Core capability-name branches violate ownership (#98 and spec 014); a new slot/service duplicates an existing interface. No production core source edits are planned.

## 2. Wire format and privacy

**Decision**: Produce nickname + `!` + ident + `@` + displayHost, leaving status selection independent.

**Rationale**: The [IRCv3 extension](https://ircv3.net/specs/extensions/userhost-in-names) defines full hostmasks in NAMES. The repository privacy contract requires presented display hosts. `MemberReplies` already obtains `PresentedIdentity.displayHostname` before building immutable snapshots. The hook does no I/O or independent host resolution.

**Alternatives considered**: Raw hostname access risks cloak disclosure; status concatenation here would duplicate multi-prefix. PROTOCTL/UHNAMES is outside scope.

## 3. Byte budget composition

**Decision**: Declare I=U+H+2 through ReplyLimits, compose with installed status P=2, retain generic packing and validation.

**Rationale**: [spec-014 budgets](../014-capability-plugin-spi/contracts/reply-budgets.md), `ReplyLayout.NAMES_MEMBER`, and `ReplyBudgetCatalog` already support this formula. U=27 and H=63 currently imply I=92. Bounds are serialized UTF-8 bytes beyond nickname. Discovered disabled providers count, so installing the module tightens acceptance of some configurations. Test exact 512-byte fit and overflow with the real catalog, independently of other hostmask checks.

**Alternatives considered**: Character lengths undercount multibyte data; identity-only budget tests miss status composition; token truncation violates complete identity semantics.

## 4. Configuration and lifecycle

**Decision**: Follow existing discovery, enabled/disabled config and runtime controls; no dependency on multi-prefix.

**Rationale**: `ConfigurationLoader.parseExtensionStates` accepts known ids and existing startup applies explicitly enabled states. ServiceLoader discovery supplies ids and descriptors. Identity/status are independent slots. Follow current captured-output semantics: output captured before DEL may follow it.

**Alternatives considered**: Default enabling changes existing operator behavior; bespoke toggles duplicate runtime infrastructure.

## 5. Harness and performance evidence

**Decision**: Provision a separate harness worktree, preserve the old freeze and existing user modifications, and record a new reviewed patch enabling and declaring both capabilities. Use the revised harness for both server revisions. Measure hot-path loads separately from functional irctest.

**Rationale**: [frozen environment](../014-capability-plugin-spi/validation/pr-100/irctest-environment.md) locates the shared harness at `/Users/gcastro/Projects/jircd/irctest`; [#101](../014-capability-plugin-spi/validation/pr-101/irctest.md) and [#102](../014-capability-plugin-spi/validation/pr-102/irctest.md) explain its missing config. Issue #117 reports the later multi-prefix-enabled result: 318 passed / 153 skipped / 3 failed; that is historical input, not a newly measured baseline here.

**Alternatives considered**: Editing the old frozen shared controller silently invalidates comparisons; assuming three cases pass without running them supplies no evidence. A new NAMES load covers increased token bytes/allocations; existing delivery/JOIN loads detect collateral regressions.
