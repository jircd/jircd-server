# Data Model: Encapsulate Channel and Session State Transitions

This feature adds no persistent data. It assigns each piece of existing in-memory state one
owner, one guard and a set of invariants, and it defines the transitions that change state.

## Lock order

```text
ChannelRegistry monitor  ──►  Channel monitor  ──►  (nothing else)
```

- A thread holding a `Channel` monitor never acquires the `ChannelRegistry` monitor, and never
  holds two `Channel` monitors at once. `leaveAll` releases each channel's monitor before taking
  the next.
- No monitor is held while enqueueing to a `SessionWriter` or while calling extension code. The
  only foreign code run under the monitors is the JOIN admission predicate, which must not block
  (documented today, still required).
- `NicknameRegistry` stays lock-free (`ConcurrentHashMap`) and is never called while a monitor
  is held.

## ChannelRegistry (channel directory)

| State | Guard | Notes |
|---|---|---|
| `byFoldedName: LinkedHashMap<String, Channel>` | registry monitor | insertion order gives bare NAMES/LIST their stable order |

**Invariants**
- R-1: At most one channel per case-folded name.
- R-2: After any transition returns, no registered channel is empty.
- R-3: For every channel `c` and session `s`: `s ∈ c.members()` ⇔ `c ∈ s.channelMemberships()`.
  This holds whenever the registry monitor is not held by a transition.
- R-4: A session in `CLOSING` is never added to a channel.

**Transitions** (all under the registry monitor, then the channel monitor):

| Operation | Precondition checked inside | Effect | Result |
|---|---|---|---|
| `join(name, session, admission)` | session not `CLOSING`; already a member → no change, admission skipped; else `admission.test(channel)` | get-or-create, add member (first member → operator), add membership on session, consume invitation (inside admission); if `CLOSING` or refused, remove the channel just created only if still empty and still the registered instance (retain an existing channel) | `Optional<JoinResult>`: empty if `CLOSING` or refused; an existing member returns `JoinResult` with `alreadyMember=true`, no state change and no admission |
| `part(channel, session)` | `session ∈ members` | capture members (before), remove member + op + voice, remove session membership, drop if empty | `Optional<Departure>`, empty → ERR_NOTONCHANNEL |
| `kick(channel, kicker, targetNick)` | kicker is operator; target found by folded nick | capture members (before), remove target fully, drop if empty | `KickOutcome` (`NotOperator` / `NoSuchMember` / `Kicked(Departure)`) |
| `leaveAll(session)` | none (cleanup has already claimed `CLOSING`) | for each membership: remove, capture remaining members, drop if empty | `Set<ClientSession>` neighbours, de-duplicated, self excluded |

## Channel

| State | Guard | Mutated only by |
|---|---|---|
| `members`, `operators`, `voiced` | channel monitor | `addMember`/`removeMember` (package-private, from registry transitions); `grantOperator`/`revokeOperator`/`grantVoice`/`revokeVoice` |
| `activeModes` | channel monitor | `setMode`/`clearMode` |
| `bans` | channel monitor | `addBan` (cap 100), `removeBan` |
| `invited` (folded nicks) | channel monitor | `invite`, `consumeInvitation` (from JOIN admission) |
| `topic`, `memberLimit`, `key` | volatile field | existing setters (single-field writes, no cross-field invariant) |
| `createdAt`, `name` | immutable | — |

All collection accessors return unmodifiable views.

**Invariants**
- C-1: `operators ⊆ members` and `voiced ⊆ members`. Grants to a non-member are refused
  (return `false`). Today a MODE +o racing that member's PART can leave a ghost operator. With
  sequential use there is no change, because the handler already rejects non-members with
  ERR_USERNOTINCHANNEL.
- C-2: `+s` and `+p` are never both active. `setMode(SECRET)` clears `PRIVATE` and vice versa
  in one step.
- C-3: `bans.size() ≤ MAX_BANS`. Masks are unique.
- C-4: The first member added to an empty channel becomes an operator.
- C-5: `memberListing()` shows each member once, prefixed `@` if an operator, else `+` if
  voiced, in member iteration order.

## ClientSession

| State | Guard | Mutated only by |
|---|---|---|
| `channelMemberships` | written only while the corresponding channel's monitor is held (inside registry transitions) | `addMembership`/`removeMembership` (package-private) |
| `userModes` | concurrent set; single-element changes | `setUserMode`/`clearUserMode`, `grant/revokeAdministratorPrivilege` |
| `nickname` | volatile | `setNickname` (NICK handler, after `NicknameRegistry.claim`) |
| `lifecycle` | `ConnectionLifecycle` (atomic) | unchanged; `closeIfNotAlreadyClosing` is the disconnect claim |

`channelMemberships()` and `userModes()` return unmodifiable views.

**Invariants**
- S-1: After `DisconnectCleanup.cleanup` returns, `channelMemberships()` is empty and the
  nickname is released.
- S-2: `UserMode.OPERATOR ∈ userModes` if and only if administrator privilege was granted and
  not revoked. Generic `setUserMode` / `clearUserMode` reject OPERATOR with
  `IllegalArgumentException`; only administrator grant/revoke operations manage operator mode.

## Result types (new, immutable records)

- **`JoinResult(Channel channel, Set<ClientSession> recipients, boolean grantedOperator,
  boolean alreadyMember, List<String> memberListing)`**: `recipients` = members after insertion, `memberListing` =
  NAMES tokens captured under the same lock.
- **`Departure(Channel channel, ClientSession departed, Set<ClientSession> recipients)`**:
  `recipients` = members before removal.
- **`KickOutcome`**: sealed interface with `NotOperator`, `NoSuchMember`,
  `Kicked(Departure departure)`.

## Session/channel state transitions (lifecycle view)

```text
            join (admitted, not CLOSING)
 not-member ───────────────────────────────► member ──grantOperator/grantVoice──► member(+o/+v)
     ▲                                         │                                      │
     └──────────── part / kick / leaveAll ─────┴──────────────────────────────────────┘
                   (removes member, +o and +v together; drops empty channel)

 session: CONNECTED ──closeIfNotAlreadyClosing──► CLOSING ──leaveAll + release nick──► closed
                                                   (join refused from here on)
```
