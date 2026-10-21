# Feature Specification: Modular IRCv3 Chat Server

**Feature Branch**: `001-ircv3-server`

**Created**: 2026-08-15

**Status**: Draft

**Input**: User description: "Build a modular IRCv3 server in Java with the latest capabilities"

## Clarifications

### Session 2026-08-15

- Q: Should this be a single standalone server, or must multiple server instances be able to link together into one federated network? → A: Start standalone; architecture must support adding server-to-server federation as a later, independently addable module without a core redesign.
- Q: Should the server own and manage user accounts and channel-registration/ownership records itself, or should it only verify identities against an external, separately operated account system? → A: Identity verification MUST support external account systems (pluggable), AND the initial release MUST ship a basic, built-in account module (its own minimal account store) as the default pluggable implementation.
- Q: Which specific IRCv3 capabilities must the server support in its initial release? → A: Minimal core set only — `message-tags` (structured metadata on messages), `server-time` (accurate timestamps), and `echo-message` (delivery confirmation of a client's own sent messages). Broader capabilities (SASL beyond the minimum needed for Story 3, account-notify, away-notify, batch, chathistory, etc.) are deferred to a later iteration.
- Q: Is authentication (Story 3) mandatory for all connections, and what identity-uniqueness rules are non-negotiable? → A: Authentication remains optional per-connection at initial release — unauthenticated clients continue to be served under standard IRC behavior. What is non-negotiable, per core IRC protocol requirements, is that nicknames and channel names are each a single, server-wide unique namespace: a nickname or channel name identifies exactly one client/channel at a time, with no duplicates permitted regardless of authentication status.
- Q: Must every client connection be encrypted in transit? → A: No — encryption is not part of the core IRC protocol. The server MUST offer an encrypted (TLS) connection option, but MUST also continue to accept unencrypted connections; encryption is a per-connection/administrator choice, not a mandatory requirement for all clients.
- Q: Does "modular" require that modules can be loaded and unloaded while the server keeps running, or is a restart acceptable to apply a module change? → A: Modules MUST be enable/disable-able by an administrator, and that change MUST take effect without restarting the running server process — no restart or reload interruption is acceptable for a module state change.
- Q: When two clients attempt to claim the same nickname at nearly the same instant, how must the server resolve the race? → A: First-registration-wins: the server MUST treat nickname claims atomically against its live state, so whichever request it commits first holds the nickname, and every other concurrent or later request for that same name is rejected with the standard "nickname in use" error (FR-002) — consistent with RFC 2812's ERR_NICKNAMEINUSE (433), which the original IRC protocol defines for exactly this case.
- Q: How is channel-operator status determined, and how does that interact with the account module? → A: By default (classic IRC behavior), the first client to join a channel becomes its operator. When the account module (FR-023/FR-024) is enabled, a channel MAY instead be registered/owned by an account and a nickname MAY be registered to an account (NickServ/ChanServ-style, used here as the familiar reference pattern, not a mandated command set); wherever such registration exists, it takes priority over the classic first-join rule — a registered channel's operator authority comes from its account-system ownership/access records, and by default an unauthenticated or wrongly-authenticated client MUST NOT be able to claim a nickname already registered to another account (administrator-configurable to allow).
- Q: Is User Story 3 (authentication) mandatory for the first iteration? → A: No — Story 3 is deferred to a later release. The first iteration MUST be fully usable via Stories 1, 2, and 4 alone. All requirements that exist solely to support Story 3 (FR-009, FR-010, FR-023, FR-024, FR-026, FR-027) and its success criterion (SC-007) are deferred with it; the classic first-join-gets-operator rule (FR-013) is the only channel-operator behavior in effect until the account module is later built.
- Q: Does treating federation as "just another module" (like Story 4's capabilities/moderation-tool modules) correctly capture what a future server-to-server implementation would need? → A: No — that framing was reconsidered as an oversimplification. Federation introduces a server-to-server trust boundary and distributed state (network-wide identity uniqueness, cross-server message routing, netsplit handling) that a client-facing module does not; it is not required to fit the FR-011 module abstraction, and its actual extension mechanism is left as a planning-phase decision made when federation is scoped. What this specification commits to now instead is narrower and testable: FR-021 (standalone operation, federation out of initial scope) plus FR-022, which names the specific core behaviors — nickname/channel uniqueness scope, channel message delivery, connection-loss handling — that MUST be implemented in a way that doesn't foreclose extending them later.
- Q: Once federation exists, must every linked server run the same active modules, and must authentication stay consistent network-wide? → A: Yes to both. When federation is introduced, linked servers MUST present a consistent set of active modules to clients network-wide — a client's experience must not depend on which linked server it connects to (FR-028). Where the account module is in use in a federated network, account/identity verification and registration records MUST be managed by a single authoritative source shared across all linked servers, not independently per-instance (FR-029). Neither constraint requires anything of the initial, standalone release; they bound how a future federation effort must behave.

### Session 2026-08-16

- Q: Must a client already be a member of a channel to send it a PRIVMSG/NOTICE, or can any registered client message a channel it hasn't joined unless members-only mode is explicitly set? → A: Membership is NOT required by default — any registered client can PRIVMSG/NOTICE a channel it hasn't joined; members-only (FR-013/FR-043) is what restricts this.
- Q: What should the wire-protocol validation rules be for a channel name? → A: RFC 2812 channel grammar — a leading `#` (standard channel type only, no `&`/`+`/`!` variants) followed by 1 to 49 additional characters, excluding space, comma, and control characters (50 characters total, maximum) — the same rigor already applied to nickname format (contracts/irc-protocol-commands.md "Connection Registration Grammar"), rejected with a dedicated error distinct from "nickname in use"-style errors.
- Q: What should happen when the server is at capacity and a new client tries to connect? → A: No explicit server-level limit — the server does not enforce its own connection cap or send a dedicated rejection message; capacity is bounded only by OS/network resources and whatever an administrator configures at the deployment layer. SC-003's 1,000-connection floor is a sustained-operation target, not a hard ceiling with special in-protocol handling.
- Q: Should the server enforce a maximum protocol line length, and how should an over-length message be handled? → A: 512-byte base line limit (command+params, CRLF-inclusive, classic IRC), plus the IRCv3 message-tags specification's required server-side allowance of up to 4096 additional bytes for the tags section (since FR-025 already implements message-tags). A line exceeding either budget MUST be rejected under FR-015's existing malformed-message handling, not silently truncated.
- Q: Should the server enforce/assume a specific character encoding for message text, or treat it as an opaque byte stream? → A: UTF-8, validated — message text (`PRIVMSG`/`NOTICE` bodies, topics, realnames, and channel names) MUST be valid UTF-8; the server MUST reject a message containing an invalid UTF-8 byte sequence in one of these fields as malformed (FR-015), not pass it through, mistranscode it, or silently discard just the invalid portion.

### Session 2026-08-17

- Q: When every operator of a channel revokes their own operator status (or leaves) without granting it to anyone else first, what should happen to that channel? → A: Leaderless until admin intervenes — the channel is left with members but no operator; only an administrator can restore order (force-join via FR-057, then self-op via FR-058). No automatic promotion occurs.
- Q: FR-011 requires a configuration-file change (enabling/disabling an extension) to take effect without a server restart — how does the running server learn the file changed? → A: Explicit reload trigger only, via two equivalent paths — a `SIGHUP` signal sent to the process, and an in-band, administrator-privileged `REHASH` command (FR-032/FR-033) for when file-system/host access isn't available (Story 6's peer-tier path to Story 4's). No automatic file-watching; editing the file alone has no effect until one of these two triggers fires.
- Q: When a client issues the administrator-privilege command (`OPER`, FR-034) with invalid credentials, what should the server do beyond rejecting it? → A: Refuse the attempt with a clear error and log it as a security-relevant event (FR-019), the same as every other permission error — but additionally track failed `OPER` attempts per normalized source-address bucket across reconnects and usernames, and disconnect the client when an administrator-configurable threshold is reached within the lockout window (brute-force lockout), the same "reasonable, administrator-configurable default" posture FR-016's rate limiting already uses.
- Q: When a member with the voice privilege (FR-045) leaves a channel and later rejoins, do they keep voice, or does an operator need to grant it again? → A: Lost on part, must be re-granted — voice (and, symmetrically, operator status) is tied to current membership, not the nickname's history with the channel; leaving clears all per-member privileges, the classic IRC behavior.
- Q: When the hostname-cloaking extension is disabled while a client is still connected and was already benefiting from it, does that client's presented hostname change immediately, or stay cloaked until they reconnect? → A: Immediate — the moment the extension is disabled (via FR-011's `SIGHUP`/`REHASH` reload), every currently-connected cloaked client's presented hostname reverts to the real value right away, consistent with SC-005's "connected and new clients" wording.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Connect and Chat in Real Time (Priority: P1)

A person using any standard IRC client connects to the server, registers a
nickname, joins a channel, and exchanges messages with other people
connected to that channel in real time.

**Why this priority**: This is the core reason the server exists — without
reliable connection, registration, and message delivery, nothing else
matters. It is the minimum viable product.

**Independent Test**: Connect two separate IRC clients to the server,
register distinct nicknames, join the same channel, and confirm each
client sees messages sent by the other within a second.

**Acceptance Scenarios**:

1. **Given** the server is running and reachable, **When** a client
   connects and sends valid registration commands (nickname and user
   info), **Then** the server confirms registration and the client is
   recognized as an active user.
2. **Given** two registered clients have joined the same channel, **When**
   one sends a message to the channel, **Then** the other receives the
   message attributed to the correct sender.
3. **Given** a client attempts to register with a nickname already in use
   by another connected client, **When** the registration is submitted,
   **Then** the server rejects it with a clear "nickname in use" response
   and the client can retry with a different nickname.
4. **Given** a channel operator sets a topic on their channel, **When**
   another member views the channel, **Then** they see the current topic;
   **When** a non-operator member attempts to change it, **Then** the
   server rejects the attempt with a clear permissions error.
5. **Given** several channels currently have members, **When** a client
   requests the list of active channels, or the member list of one of
   them, **Then** the server returns accurate, current results without
   requiring the requesting client to have joined that channel first.

---

### User Story 2 - Discover and Use Enhanced Capabilities (Priority: P2)

A person using a modern IRC client that supports capability negotiation
connects to the server, discovers which enhanced protocol capabilities are
available, and enables the ones its client supports so it can display
richer information (such as message timestamps and delivery
confirmations) without breaking clients that do not support them.

**Why this priority**: Capability negotiation is what distinguishes an
"IRCv3" server from a legacy IRC server — it is the mechanism that lets
the server offer modern features while remaining backward compatible with
older clients, so it is core to the product's identity but depends on
basic connectivity (Story 1) already working.

**Independent Test**: Connect a capability-aware test client, request the
list of available capabilities, negotiate a subset, and confirm the
server's subsequent messages include the negotiated enhancements (e.g., a
timestamp on each message) while a plain client connected at the same time
sees standard, unaugmented messages.

**Acceptance Scenarios**:

1. **Given** a client connects and requests the capability list before
   registering, **When** the server responds, **Then** the client receives
   a complete, accurate list of capabilities currently available.
2. **Given** a client requests a specific supported capability, **When**
   the request is processed, **Then** the server acknowledges it and
   begins including that capability's enhancements in relevant messages
   for that client's session.
3. **Given** a client requests a capability the server does not support,
   **When** the request is processed, **Then** the server clearly declines
   that specific capability without breaking the rest of the negotiation
   or disconnecting the client.

---

### User Story 3 - Authenticate to Protect an Identity (Priority: P3, Deferred)

A person with a registered account authenticates during connection so that
other users and the server can trust their claimed identity, preventing
someone else from impersonating them by using the same nickname.

**Why this priority**: Identity trust becomes important once a community
has recurring members and protected nicknames/channels, but a server can
deliver value (Stories 1–2) before this is required — so it follows
core chat and capability negotiation in priority. **This story is not
mandatory for the first iteration**: the server MUST be fully usable
(Stories 1–2, and Story 4's extensibility) without it, and it is deferred
to a later release. It is documented now so the boundary it requires (the
account module) can be designed for without committing to build it yet.

**Independent Test**: Attempt to authenticate a client with valid account
credentials and confirm the session is marked as authenticated to other
clients; attempt authentication with invalid credentials and confirm it is
rejected with a clear error and the connection is not marked as
authenticated.

**Acceptance Scenarios**:

1. **Given** a client provides valid account credentials during
   authentication, **When** the server verifies them, **Then** the session
   is marked authenticated and the client's verified identity is visible
   to other clients that request it.
2. **Given** a client provides invalid credentials, **When** the server
   attempts verification, **Then** the server rejects the attempt with a
   clear error and does not mark the session as authenticated.
3. **Given** an authenticated client disconnects, **When** it reconnects
   with the same credentials, **Then** it is able to re-authenticate and
   regain its verified status.

---

### User Story 4 - Tailor the Server with Optional Extensions (Priority: P4)

A server administrator enables, disables, and configures optional
extensions (for example, individual IRCv3 capabilities such as
`message-tags` or `server-time`) to match their community's needs, without
needing to modify the server's core codebase. Core protocol behavior —
including channel moderation and the capability-negotiation mechanism
itself — is always present and is not part of what this story's toggling
applies to (see FR-035, FR-036).

**Why this priority**: Extensibility is a stated goal of the product and
matters most to administrators, but the server delivers its primary value
to end users (chatting) even with a fixed default set of extensions
enabled — so administrator-facing configurability is valuable but not
blocking for the core chat experience.

**Independent Test**: As an administrator, disable an optional extension
via configuration while the server keeps running (no restart), and confirm
connected clients can no longer use that extension's functionality while
all other functionality continues to work uninterrupted; re-enable it the
same way and confirm functionality returns without a restart.

**Acceptance Scenarios**:

1. **Given** the administrator has a list of optional extensions, **When**
   they disable one via configuration while the server is running, **Then**
   the server no longer offers or accepts requests for that extension's
   functionality, without the server process being restarted.
2. **Given** an extension is disabled, **When** the administrator
   re-enables it via configuration while the server is running, **Then**
   its functionality becomes available again without requiring changes
   beyond configuration and without restarting the server process.
3. **Given** an administrator provides an invalid extension configuration,
   **When** the server loads its configuration, **Then** the server
   reports a clear configuration error identifying the problem instead of
   starting in a broken or partially-configured state.

---

### User Story 5 - Moderate a Channel (Priority: P5)

A channel operator manages who can participate in their channel and how,
using standard moderation actions (such as removing a disruptive user or
restricting who may speak) to keep the community usable.

**Why this priority**: Moderation matters once channels have active,
larger communities, but small or trusted communities can function
initially without it — it rounds out the product rather than gating its
core value.

**Independent Test**: As a channel operator, remove a user from a channel
and confirm they are no longer a member and are notified of the removal;
confirm other channel members see the removal reflected.

**Acceptance Scenarios**:

1. **Given** a user is a channel operator, **When** they remove another
   member from the channel, **Then** that member is no longer part of the
   channel and all remaining members are notified of the removal.
2. **Given** a user is not a channel operator, **When** they attempt a
   moderation action, **Then** the server rejects the action with a clear
   permissions error.
3. **Given** an operator restricts who may send messages to a channel,
   **When** a non-permitted member attempts to send a message, **Then**
   the message is not delivered and the sender receives a clear
   explanation.
4. **Given** a channel is under moderated-mode restriction (Scenario 3)
   and an operator grants a member the voice privilege, **When** that
   voiced member sends a message, **Then** it is delivered — unlike a
   non-voiced, non-operator member's message under the same restriction.
5. **Given** an operator grants operator status to another member,
   **When** that member performs a moderation action (e.g., removing a
   disruptive user), **Then** it succeeds, the same as if the original
   operator had performed it.
6. **Given** an operator marks their channel secret, **When** a
   non-member requests its topic, membership list, or checks the active
   channel list, **Then** the response is indistinguishable from the
   channel not existing; **When** a current member does the same,
   **Then** they see it normally.
7. **Given** an operator adds a ban mask matching a member currently in
   the channel, **When** that member attempts to send a message,
   **Then** it is not delivered, the same as Scenario 3, and the member
   remains a channel member (not removed); **When** that same member
   later leaves and attempts to `JOIN` again while the ban is still
   active, **Then** the attempt is rejected. **Given** the operator then
   removes that ban mask, **When** the formerly-banned client attempts
   to `JOIN` again, **Then** it succeeds normally. **Given** an operator
   requests the channel's current ban list, **When** the request is
   processed, **Then** every currently-active mask is returned
   (FR-062).
8. **Given** a hostname-cloaking extension is presenting a member's
   channel-visible hostmask as something other than their real,
   underlying hostname/IP, **When** an operator adds a ban mask
   matching that member's *real* hostname/IP (not their cloaked,
   publicly-visible one), **Then** the member is muted the same as any
   other ban match — a ban is not evadable simply because a cloaking
   extension changes what other clients see (FR-062).
9. **Given** an operator marks their channel invite-only, **When** a
   client with no invitation on that channel attempts to `JOIN`,
   **Then** the attempt is rejected; **When** the operator invites that
   client by nickname and it then attempts to `JOIN` again, **Then** it
   succeeds, and the same client's invitation no longer admits a
   second, later `JOIN` after it parts. **Given** the channel is not
   invite-only, **When** any current member (not only an operator)
   invites another connected client, **Then** the invitation is
   recorded and the inviting member receives confirmation, but nothing
   about the target's ability to `JOIN` changes, since `invite-only`
   isn't restricting it. **Given** a client holds a valid invitation to
   a channel that also bans that same client, **When** it attempts to
   `JOIN`, **Then** the attempt is still rejected — an invitation
   overrides `invite-only` only, never an active ban (FR-062, FR-065).

---

### User Story 6 - Administer the Server via IRC Commands (Priority: P4)

An administrator, connected as an ordinary IRC client, grants themselves
administrator privilege via an in-band command and then issues
administrative commands — such as enabling or disabling an optional
extension, looking up a client's real hostname, forcing their way into a
channel a regular client couldn't join, or granting themselves channel-
operator status without waiting on an existing operator — directly
through the IRC protocol, without needing file system or configuration-
file access to the server host.

**Why this priority**: In-band administration is a peer capability to
Story 4's configuration-file path, not a lesser one — administering a
server that requires host/file access for every change is not practically
usable, so this shares Story 4's priority tier.

**Independent Test**: Connect as a normal client, issue the
privilege-granting command with valid administrator credentials, confirm
privilege is granted, then issue an extension-toggle administrative
command and confirm the extension's state change takes effect for other
connected clients — all without touching the configuration file.

**Acceptance Scenarios**:

1. **Given** a connected, registered client, **When** it issues the
   administrator-privilege command with valid credentials, **Then** the
   server grants administrator privilege to that session and confirms it.
2. **Given** a session without administrator privilege, **When** it
   attempts an administrative command, **Then** the server rejects it with
   a clear permissions error and takes no action.
3. **Given** a session holding administrator privilege, **When** it issues
   a command to disable an enabled extension, **Then** the extension
   becomes unavailable to all clients without a server restart — the same
   observable effect as a configuration-file-driven change (FR-011).
4. **Given** a session holding administrator privilege, **When** it
   requests a specific client's real hostname, **Then** the server returns
   the real, unobfuscated value even if a cloaking extension currently
   obscures that client's hostname from other clients (FR-031).
5. **Given** a session holding administrator privilege, **When** it issues
   the force-join command against a channel that a currently-active
   channel-mode flag would otherwise block a regular client from joining,
   **Then** the server joins that session to the channel anyway; **When**
   that same session, now a member of a channel that already has other
   members and an existing operator, issues the self-op command, **Then**
   the server grants it channel-operator status immediately, without
   requiring an existing operator to grant it (FR-057, FR-058).

---

### User Story 7 - Look Up Information About a User (Priority: P2)

A person using an IRC client looks up information about themselves or
about another connected user — nickname, ident, presented hostname, and
real name — the same kind of lookup virtually every IRC client performs
routinely, whether by looking up one specific user, listing a channel's
current members, or searching by a nickname pattern.

**Why this priority**: This is standard, expected IRC functionality (like
Story 2's capability negotiation, it rounds out what makes the server feel
like a complete IRC server) but the server already delivers its core value
(chatting) without it, so it isn't blocking for the MVP.

**Independent Test**: As a connected client, look up your own information
and confirm it's returned correctly; as a different, non-administrator
client, look up that same user's information and confirm you receive
their presented (not real) hostname.

**Acceptance Scenarios**:

1. **Given** a connected, registered client, **When** it looks up
   information about itself (no target, or its own nickname), **Then**
   the server returns that client's own real, unobfuscated hostname/IP —
   regardless of whether a cloaking extension is currently enabled — along
   with its nickname, ident, and real name.
2. **Given** a session holding administrator privilege, **When** it looks
   up information about a different connected client, **Then** the server
   returns that client's real, unobfuscated hostname/IP, consistent with
   FR-032's administrator visibility guarantee.
3. **Given** a session that does **not** hold administrator privilege,
   **When** it looks up information about a *different* connected client,
   **Then** the server returns only that client's presented hostname (the
   same value it would see in that client's message hostmask — obfuscated
   if a cloaking extension is enabled, the real value otherwise) — never
   that client's real, unobfuscated hostname/IP.
4. **Given** a client looks up a nickname that is not currently connected,
   **When** the lookup is processed, **Then** the server returns a clear
   "no such nickname" error rather than any user data.
5. **Given** a client that shares no channel with another, currently
   `invisible` client, **When** it searches for that client by nickname
   mask or with no argument at all, **Then** the invisible client is
   excluded from the results; **When** the same searching client is
   instead an administrator, or joins a channel the invisible client is
   also a member of and repeats the search, **Then** the invisible
   client is included normally (FR-044, FR-061).
6. **Given** a client requests the member list of a channel via the
   search command, **When** that channel is public and the requester is
   not a member, **Then** every current member is listed — including any
   `invisible` member — exactly matching what a `NAMES` query on the
   same channel would show (FR-041, FR-061).

---

### Edge Cases

- What happens when a client attempts to register with a nickname or
  username that violates protocol formatting rules (invalid characters,
  excessive length)?
- What does a client actually receive once registration succeeds, beyond
  a bare "you're welcome here" — and what happens if the administrator
  never configured a server name at all, or the deployment host's own
  hostname doesn't contain a "." either?
- If a future server extension contributing enough additional
  channel-mode flags (FR-043) made the feature-support advertisement
  (FR-055) too long for one line, does the server split it across
  multiple lines the way real IRC servers do, or truncate/fail?
- What happens when a client registers "Alice," disconnects, and a
  different client then tries to register "alice" — does the server
  treat these as the same nickname (rejecting the second) or two
  different ones?
- How does the server handle a client that sends messages faster than an
  acceptable rate (flooding), whether accidental or malicious?
- What happens when a client disconnects abruptly (no clean quit) while
  still a member of one or more channels?
- What happens when a client's connection goes silent without *any*
  TCP-level signal at all — no clean quit, no abrupt-close error, just a
  network path that stopped delivering data (e.g., the client's machine
  lost power, or a NAT/firewall silently dropped an idle connection) — how
  does the server ever notice and clean it up?
- How does the server respond to a malformed or incomplete protocol
  message that cannot be parsed?
- What happens when a client sends a channel mode flag other than the two
  this release implements (moderated-mode, members-only) — e.g.,
  invite-only or a channel key — or queries a channel's current modes
  with no flag argument at all? What happens when a client sends a
  user-level mode change or query, which this release doesn't implement
  at all?
- How does the server behave when an optional extension fails to start or
  encounters an internal error while running — does the rest of the
  server continue operating?
- *(Applies once a mode-contributing extension exists — no extension
  contributes one in this release)* What happens to a channel that
  already has an extension-contributed mode flag set when the
  administrator disables that extension — does the flag disappear, does
  it linger inertly, or something else?
- *(Applies once a connection-admission extension exists — no extension
  contributes one in this release, FR-066)* When an administrator adds a
  network mask that matches a client already past registration, is that
  client retroactively disconnected, or does the new block only apply to
  future connection attempts? Also left open: whether a match is judged
  against a client's real host/IP only, or also against a presented
  value a cloaking extension (FR-031) might show — both are FR-066
  mechanism-design questions for when this extension is actually built,
  not this release's concern.
- *(Resolved — see Clarifications, Session 2026-08-17)* What happens to a
  member's voice privilege (FR-045) when they leave a channel and
  rejoin?
- *(Resolved — see Clarifications, Session 2026-08-17)* What happens if
  every operator of a channel revokes their own operator status (or
  leaves) without first granting it to anyone else (FR-046)?
- What happens if an operator marks a channel both private and secret at
  once — does one silently override the other, or is it rejected as
  contradictory (FR-047)?
- Can an administrator investigating abuse still see a private/secret
  channel's topic and membership, the same way they can already see a
  cloaked member's real hostname (FR-032), or is that visibility
  guarantee limited to identity information (FR-047)?
- What happens when a client negotiates a capability, and mid-session the
  administrator disables the extension providing that capability?
- How does the server respond when a channel or nickname name exceeds
  defined length or character constraints?
- What happens when the maximum number of concurrent connections is
  reached and a new client attempts to connect?
- *(Resolved — see Clarifications, Session 2026-08-17)* What happens when
  a client issues the administrator-privilege command (FR-034) with
  invalid credentials?
- *(Resolved — see Clarifications, Session 2026-08-17)* What happens to
  already-cloaked clients' presented hostnames when the cloaking
  extension is disabled while they remain connected?
- What does a client see when it looks itself up while a cloaking
  extension is obscuring its own hostname from everyone else — its real
  value (since it's the client's own data) or the same obscured value
  other clients see?
- *(Applies once Story 3 / the account module is implemented — not
  applicable to the initial release)* How does the server handle an
  authenticated client's underlying account being deleted or suspended
  mid-session?
- *(Applies once Story 3 / the account module is implemented — not
  applicable to the initial release)* What happens when an unregistered
  channel already has a first-join operator, and a member later registers
  ownership of that channel to an account — does the account's authority
  immediately supersede the existing operator (per FR-026), and how are
  current members notified?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The server MUST accept incoming client connections and allow
  a client to register a unique nickname and associated user information
  before participating in chat. The command that supplies that user
  information (`USER`) is strictly one-shot per connection: it MUST be
  rejected with a clear, specific error — distinct from FR-015's other
  malformed-message cases — if a session that has already processed one
  `USER` command sends a second, whether the repeat arrives before or
  after registration completes. Re-sending it MUST NOT re-trigger the
  registration-completion burst, alter the session's already-established
  `ident`/real name, or otherwise be silently accepted as a no-op;
  `NICK` is unaffected by this restriction and remains usable at any
  time to change an already-registered session's nickname (FR-002).
- **FR-002**: The server MUST reject registration attempts that use a
  nickname currently claimed by another connected client, and MUST inform
  the requesting client clearly. Nickname claims MUST be resolved
  atomically against the server's live state: when multiple clients
  request the same nickname concurrently, exactly one MUST succeed (the
  one the server commits first) and every other request for that name —
  concurrent or later — MUST be rejected with the same "nickname in use"
  error, with no window in which two clients both hold the name. This
  uniqueness check is case-insensitive (FR-052) — "Alice" and "alice"
  are the same nickname, not two different ones.
- **FR-003**: The server MUST allow registered clients to create or join
  named channels and to leave channels they have joined. Channel names
  MUST form a single, server-wide unique namespace: a given channel name
  identifies exactly one channel at a time, so joining a name that
  already exists MUST add the client to that existing channel rather than
  creating a separate, duplicate one. This uniqueness check is
  case-insensitive (FR-052) — "#Foo" and "#foo" are the same channel.
- **FR-004**: The server MUST deliver messages sent to a channel to every
  other client currently joined to that channel, attributed to the correct
  sender. Sending a channel message MUST NOT itself require the sender to
  be a member of that channel — membership is a precondition only when
  the channel's members-only restriction (FR-013/FR-043) is active; by
  default (members-only unset), any registered client MAY send to any
  channel it hasn't joined, and delivery still reaches only the
  channel's current membership as stated above.
- **FR-005**: The server MUST support direct, private messaging between
  two registered clients without requiring a shared channel.
- **FR-006**: The server MUST support capability negotiation, allowing a
  connecting client to request the list of capabilities the server
  currently offers before completing registration.
- **FR-007**: The server MUST allow a client to request one or more
  specific capabilities and MUST respond indicating which were accepted
  and which were declined.
- **FR-008**: The server MUST continue to support clients that do not
  perform capability negotiation at all, providing them standard,
  unaugmented protocol behavior.
- **FR-009** *(Deferred — not required for initial release; supports
  Story 3)*: The server MUST support verifying a client's claimed account
  identity during connection registration via an authentication exchange,
  and MUST reflect authenticated status to other clients that request it.
  Authentication MUST remain optional per-connection whenever it is
  implemented: a client that does not authenticate MUST still be able to
  register a nickname and use standard chat functionality under core IRC
  behavior.
- **FR-010** *(Deferred — not required for initial release; supports
  Story 3)*: The server MUST reject invalid authentication attempts with
  a clear error and MUST NOT mark a session as authenticated when
  verification fails.
- **FR-011**: The server MUST allow an administrator to enable or disable
  individual optional extensions via configuration, without requiring
  changes to the server's core codebase, and without requiring the running
  server process to be restarted for the change to take effect. Editing
  the configuration file alone does not apply a change — the server MUST
  provide two equivalent, explicit reload triggers: a `SIGHUP` signal
  sent to the running process, and (once the `admin` extension of Story 6
  is enabled) an in-band, administrator-privileged `REHASH` command
  (FR-032/FR-033) for deployments without file-system/host access. There
  is no automatic file-watching.
- **FR-012**: The server MUST report a clear, specific error when an
  administrator's configuration is invalid, rather than silently ignoring
  the problem or starting in an inconsistent state.
- **FR-013**: The server MUST support designated channel operators
  performing standard moderation actions, including removing a member from
  a channel and restricting who may send messages to it. By default, a
  channel's first operator MUST be the client whose join created that
  channel (classic first-join-gets-operator behavior). FR-026 and FR-027
  define how this default is later superseded once the (currently
  deferred) account module manages the channel or nickname involved; for
  the initial release, the classic default is the only behavior in
  effect.
- **FR-014**: The server MUST reject moderation actions attempted by
  clients who do not hold the required channel privileges, with a clear
  permissions error.
- **FR-015**: The server MUST detect and reject malformed protocol
  messages without crashing or disconnecting unrelated clients. Command
  recognition MUST be case-insensitive — `join`, `Join`, and `JOIN` MUST
  all match the same command — the same way `PRIVMSG`/`NOTICE`/`JOIN`
  etc. are documented throughout this specification in a single
  canonical case only for readability, not to imply that case is
  significant on the wire.
- **FR-016**: The server MUST limit the rate of messages or commands
  accepted from a single connection to protect overall service
  availability for other clients.
- **FR-017**: The server MUST clean up a client's channel memberships and
  notify affected channels when that client disconnects, whether
  gracefully or unexpectedly.
- **FR-018**: The server MUST offer clients the option to connect over an
  encrypted (TLS) connection, and MUST also continue to accept
  unencrypted connections. Encryption is a per-connection choice made by
  the client (or an administrator-configured policy for a given
  deployment), not a mandatory requirement of the protocol itself.
- **FR-019**: The server MUST log security-relevant events (including
  failed authentication attempts and rejected moderation actions) in a
  form an administrator can review.
- **FR-020**: The server MUST continue serving unaffected clients when a
  single optional extension fails to start or encounters a runtime error.
- **FR-021**: The server MUST operate as a complete, self-sufficient
  standalone deployment — a single instance MUST NOT require any other
  server instance to be present to serve clients. Server-to-server
  federation (linking multiple instances into one network experienced by
  end users as a single chat network) is out of scope for the initial
  release. The core design MUST NOT foreclose adding federation in a
  later release, but this specification does NOT require federation to
  fit the same independent-extension abstraction used for individual
  capabilities and command sets (FR-011): federation introduces a
  server-to-server trust boundary and distributed state that a
  client-facing extension does not, so it may need its own extension
  mechanism, to be defined when federation is actually planned rather
  than assumed now.
- **FR-022**: To keep a future federation effort viable without a full
  redesign, the initial release's implementation of the following
  client-facing behaviors MUST be treated as later-extensible rather than
  hard-assumed to be server-local: nickname and channel uniqueness
  scope (FR-002, FR-003 — "unique on this server" today, potentially
  "unique across the network" later), message delivery to a channel's
  membership (FR-004 — membership may later span servers), and
  connection-loss cleanup and notification (FR-017 — a "disconnect" may
  later originate from a peer server rather than the client). No
  federation behavior itself is required for the initial release; this
  requirement only constrains how the in-scope behaviors above are
  implemented so they remain compatible with a later change.
- **FR-023** *(Deferred — not required for initial release; supports
  Story 3)*: The server MUST verify account identities through a
  pluggable account-verification interface, allowing an administrator to
  configure it against an external, separately operated account system
  instead of (or in addition to) the server's own account store.
- **FR-024** *(Deferred — not required for initial release; supports
  Story 3)*: The server MUST ship with a basic, built-in account module —
  a minimal account store covering account creation and credential
  verification — enabled by default, so the server is usable for
  authentication (Story 3) without requiring an external account system
  to be deployed first. Whenever this module is implemented, it MUST
  store credentials securely: credentials MUST NOT be persisted in plain
  text, and MUST be protected using an industry-standard, purpose-built
  method for the credential type (e.g., a salted, computationally-expensive
  hash for passwords) such that recovering the original credential from
  stored data alone is infeasible.
- **FR-025**: The server MUST offer the following enhanced capabilities in
  its initial release: structured message metadata (`message-tags`),
  accurate message timestamps (`server-time`), and delivery confirmation
  of a client's own sent messages (`echo-message`). The authentication
  capability needed to support Story 3 is deferred along with that story
  (see FR-023). Other enhanced capabilities (e.g., presence/away status,
  multi-device account awareness, batched message delivery, chat history
  replay) are likewise out of scope for initial release and deferred to a
  later iteration.
- **FR-026** *(Deferred — not required for initial release; depends on
  the account module, FR-023/FR-024)*: When the account module is
  enabled, an account MUST be able to register ownership of a channel.
  Where a channel is registered, its operator/privilege assignments MUST
  be derived from that account's stored access records rather than from
  join order, and this registered-ownership authority MUST take priority
  over the classic first-join-gets-operator default (FR-013) whenever the
  two would conflict — for example, an authenticated owner joining a
  registered channel after others are already present MUST still receive
  its operator authority. Until the account module is implemented, every
  channel's operator status follows the classic default only.
- **FR-027** *(Deferred — not required for initial release; depends on
  the account module, FR-023/FR-024)*: When the account module is
  enabled, an account MUST be able to register ownership of a nickname.
  By default, the server MUST reject an attempt by an unauthenticated
  client, or a client authenticated to a different account, to claim a
  nickname already registered to another account. An administrator MUST
  be able to configure the server to allow this instead (e.g., permitting
  the claim with a warning rather than a rejection).
- **FR-028** *(Deferred — constrains a future federation effort; not
  applicable to the initial, standalone release)*: Once federation
  (FR-021) is introduced, every server linked into the same network MUST
  present a consistent set of active extensions to clients — an optional
  capability or other extension enabled on one linked server MUST be
  enabled (or consistently unavailable) network-wide, so a client's
  experience does not depend on which linked server it happens to be
  connected to.
- **FR-029** *(Deferred — constrains a future federation effort; not
  applicable to the initial, standalone release)*: Once federation
  (FR-021) is introduced and the account module (FR-023/FR-024) is in
  use, account verification and account/nickname/channel registration
  records MUST be managed by a single authoritative source shared across
  every linked server in the network. No linked server instance may
  operate its own independent, potentially divergent account store.
- **FR-030**: The server MUST present each client's identity, on protocol
  messages that include a sender (e.g., channel and direct messages, join/
  part/quit notifications), in the standard `nickname!ident@hostname` form,
  where `ident` and `hostname` are derived from the client's connection.
- **FR-031**: The server MUST support an optional extension that replaces
  the hostname/IP portion of a client's presented identity (FR-030) with an
  obfuscated value shown to other clients, while the server continues to
  record that client's real, unobfuscated hostname/IP internally.
  Administrators MUST be able to view a client's real hostname/IP at any
  time regardless of whether cloaking is currently applied to it (see
  FR-032). If this extension is disabled while a client it was cloaking
  is still connected (FR-011's reload path), that client's presented
  hostname MUST revert to its real value immediately, for every other
  connected client observing it from that point forward — not merely
  for new connections — consistent with SC-005's "connected and new
  clients" guarantee.
- **FR-032**: The server MUST provide an in-band administrative command
  interface, available over the IRC protocol itself, through which an
  authorized administrator can perform administrative actions — at
  minimum, enabling/disabling optional extensions (FR-011) and viewing a
  client's real, unobfuscated hostname/IP (FR-031) — without requiring
  direct access to the server's configuration file or host filesystem.
- **FR-033**: The server MUST restrict administrative commands (FR-032) to
  sessions holding administrator privilege, and MUST reject attempts from
  any other session with a clear permissions error — mirroring the
  channel-operator privilege pattern (FR-014), but as a distinct,
  server-wide privilege independent of channel-operator status.
- **FR-034**: The server MUST support granting administrator privilege to
  an already-registered session via an in-band credential-verification
  command (classic IRC OPER-style), checked against administrator
  credentials defined in the Server Configuration. This mechanism MUST be
  independent of the deferred account module (FR-023/FR-024), since
  administrative access must not depend on functionality that is out of
  scope for the initial release. Administrator credentials stored in the
  Server Configuration MUST be protected the same way as FR-024 requires
  for the account module (not plain text; industry-standard hashing). A
  successful grant MUST also set the `operator` user mode (`+o`, FR-044)
  on that session in the same act — a client observing that session's
  user modes (via its own `MODE` query, FR-044, or another client's
  `WHOIS`, FR-037) MUST see it reflected immediately, not only through
  administrator-only commands becoming usable. A failed `OPER` attempt
  (invalid credentials) MUST be rejected with a clear error and logged
  as a security-relevant event (FR-019), the same as any other
  permission error (FR-014/FR-033) — but the server MUST additionally
  track failed `OPER` attempts per normalized source-address bucket across
  connections and usernames. IPv4 uses /32; IPv6 uses the configured
  `ipv6ConnectionLimitPrefix` (default /64); IPv4-mapped addresses normalize
  to IPv4. The server MUST disconnect the client when the administrator-
  configurable `operFailureThreshold` is reached within the fixed
  `operLockoutSeconds` window, starting when the first failed verification
  completes. Reconnecting MUST NOT reset that source's failures. During
  lockout, OPER MUST be refused without password verification and the
  client disconnected. Success below threshold MUST clear that source's
  failures; window expiry permits verification again. The tracker is capped
  at 10,000 entries; new sources use per-session failure counting at capacity,
  still verifying credentials and disconnecting at the threshold. An absent
  source address uses a per-connection key. This follows the same
  "reasonable, administrator-configurable default" posture FR-016's rate
  limiting already establishes for abuse protection.
- **FR-035**: The capability-negotiation mechanism itself (FR-006, FR-007,
  FR-008 — a client's ability to request the capability list and negotiate
  a subset) MUST always be available and MUST NOT be an optional extension
  subject to FR-011 toggling. Only the individual capabilities it offers
  (FR-025's `message-tags`, `server-time`, `echo-message`) are optional,
  independently toggleable extensions; disabling all of them MUST still
  leave a client able to perform capability negotiation and simply receive
  an empty or reduced capability list.
- **FR-036**: Channel moderation (FR-013, FR-014 — channel-operator
  designation and standard moderation actions) is core protocol behavior,
  equivalent to user modes and channel modes in standard IRC, and MUST
  always be available. It MUST NOT be an optional extension subject to
  FR-011 toggling; an administrator MUST NOT be able to disable moderation
  capability network-wide.
- **FR-037**: The server MUST support a user-lookup command allowing a
  registered client to query information — at minimum nickname, ident,
  presented hostname, and real name — about a target nickname, or about
  themselves if no target is given. Like channel moderation (FR-036) and
  capability negotiation (FR-035), this is core protocol behavior and
  MUST NOT be an optional extension subject to FR-011 toggling. If the
  target currently holds the `operator` user mode (`+o`, FR-044), the
  lookup MUST additionally indicate that — unlike the hostname
  resolution FR-038 defines, this indication is visible to *any* querying
  client, not gated by administrator privilege or self-lookup, matching
  standard IRC's `WHOIS` behavior (an operator's status is public
  information; their real hostname is not).
- **FR-038**: The hostname/IP value FR-037's lookup returns for the target
  MUST be resolved as follows, evaluated in order:
  1. If the querying client **is** the target (a self-lookup), the server
     MUST return the target's real, unobfuscated hostname/IP, regardless
     of whether a cloaking extension currently obscures it from other
     clients — a client's own data is never hidden from itself.
  2. Otherwise, if the querying client holds administrator privilege
     (FR-033), the server MUST return the target's real, unobfuscated
     hostname/IP, consistent with FR-032's `WHOHOST`-equivalent
     guarantee.
  3. Otherwise, the server MUST return the same presented (display) value
     the target's message hostmask already shows to that querying client
     (FR-030, cloak-obscured per FR-031 if a cloaking extension is
     enabled, the real value otherwise) — the server MUST NOT expose a
     target's real, unobfuscated hostname/IP to a non-administrator
     client looking up a *different* client, under any circumstance.
- **FR-039**: The server MUST detect a connection that has gone silent
  without a clean disconnect — one whose underlying network path stopped
  working without a TCP-level close ever arriving — by probing it after a
  period of inactivity and, if it does not respond within a bounded
  timeout, treating it exactly like any other disconnect for FR-017's
  cleanup and notification purposes. The server MUST also respond to a
  client-initiated liveness probe immediately, regardless of whether the
  server has probed that connection itself.
- **FR-040**: The server MUST allow any client to view a channel's current
  topic (or a clear "no topic set" indication if none has been set) —
  subject to FR-047's private/secret exception — and MUST allow a
  channel operator to set or change it, notifying current members of the
  change. The server MUST reject a topic-change attempt from a member
  who is not a channel operator with a clear permissions error, reusing
  the same channel-operator concept FR-013 already establishes rather
  than introducing a separate one.
- **FR-041**: The server MUST allow a registered client to request the
  current membership list of a named channel on demand — not only as part
  of joining it — regardless of whether the requesting client is
  currently a member of that channel, subject to FR-047's private/secret
  exception.
- **FR-042**: The server MUST allow a registered client to request a list
  of the server's currently active channels, subject to FR-047's
  private/secret exception.
- **FR-043**: The server's channel mode mechanism MUST directly and
  unconditionally implement exactly the two moderation flags FR-013
  defines (moderated-mode, members-only) as core behavior — never gated
  by whether any optional extension is enabled (FR-036). That mechanism
  MUST also be built so that an optional server extension can introduce
  additional channel-mode flags without requiring a change to the
  server's core codebase (FR-011's existing guarantee, applied to mode
  flags specifically) — for example, a future extension supporting
  registered-channel identity could add a flag for that, the way classic
  IRC networks use a registered-channel mode. A mode flag with no
  definition at all — neither core's nor a currently-enabled extension's
  — and a mode query with no flag argument, MUST both be rejected with
  the same specific, actionable "unsupported mode" error FR-013's other
  operator-gated actions use — never silently ignored, misapplied, or
  answered with a fabricated result. No extension in this release defines
  an additional flag; the mechanism exists now so one can be added later
  without revisiting this requirement. This extensibility guarantee
  covers simple on/off flags like the two FR-013 already defines; a
  channel mode that instead carries a value (e.g., a numeric limit) or a
  list (e.g., a set of masks) is a structurally different case this
  requirement does not promise a mechanism for yet — see Assumptions.
  Critically, the guarantee is not limited to flags that restrict sending
  a channel message: an on/off flag that instead restricts *joining* the
  channel (e.g., invite-only, FR-065) MUST be equally addable without a
  core-codebase change — the mechanism's extension point MUST be
  defined in terms of "which action does this flag gate," not
  hardcoded to the one action (sending) this release's two built-in
  flags happen to gate. FR-065's `invite-only` flag validated exactly
  this promise: it gates `JOIN`, not `SEND`, and required no change to
  this mechanism's shape to add.
- **FR-044**: The server MUST implement a user-level mode mechanism,
  using the same open, extension-friendly design FR-043 establishes for
  channel modes — a named, non-closed set of flags, each defined by
  either core or an optional server extension, so a future flag is an
  extension addition, not a core-codebase change. This release
  core-defines two user-mode flags: `operator` (`o`), which MUST be set
  on a session the moment it is granted administrator privilege
  (FR-034) and MUST be clearable by that session on itself at any time,
  revoking administrator privilege as the same act — the flag and the
  privilege are one fact, not two independently-tracked pieces of state
  that could drift apart — and `invisible` (`i`, FR-061), which any
  registered session MAY set or clear on itself at any time, freely,
  with no privilege check at all. Unlike channel modes, this release's
  user-mode mechanism is deliberately narrower in three ways, since
  nothing beyond these two flags motivates the fuller shape yet: (1) a
  session MAY only ever query or change its *own* user modes, never
  another session's — an attempt to target a different nickname MUST be
  rejected with a clear, specific error, the same posture FR-058 already
  takes for `SAMODE`; (2) each flag declares whether a client may set it
  directly — `invisible` may; `operator` MAY only be *set* by the
  FR-034 grant itself, never directly by a client's own mode command — a
  non-privileged session attempting to set `operator` directly MUST be
  rejected with the same privilege error FR-033's other administrator-only
  actions use, since setting it is exactly equivalent to self-granting
  administrator privilege; *clearing* a flag a session already holds,
  by contrast, is always permitted regardless of that flag's setting
  rule, the same self-revocation-needs-no-permission posture FR-046
  already grants channel operators over their own status; (3) an
  already-satisfied set (e.g., already an operator, setting `+o` again)
  or an already-absent clear (e.g., not an operator, clearing `-o`)
  MUST be treated as a harmless no-op, not an error. A user-mode
  command naming a flag neither core nor any currently-enabled
  extension defines MUST be rejected with a clear, specific error
  distinct from FR-043's channel-mode equivalent — same posture (reject
  an undefined flag explicitly), different numeral (they are different
  commands). This mechanism's flag catalog is what the Registration
  Completion Burst's user-mode letter list (FR-051) reads from — no
  longer an always-empty list now that `operator`/`invisible` exist.
  `RPL_ISUPPORT` (FR-055) advertises no equivalent token for user
  modes, matching real IRC convention — only `004`'s letter list covers
  them.
- **FR-045**: The server MUST allow a channel operator to grant a "voice"
  privilege to a specific member, and to later revoke it, the same way
  standard IRC does — restricted to operators, the same as every other
  moderation action (FR-013, FR-014). While moderated-mode restriction
  (FR-013) is active, the server MUST permit a voiced member to send
  channel messages in addition to operators — moderated mode's complete
  definition is "operators and voiced members may send," not
  "operators only." A non-existent target, or one who isn't currently a
  member of the channel, MUST be rejected with a clear error rather than
  silently granting a privilege to no one. Voice is tied to current
  membership, not the nickname's history with the channel: a member who
  leaves and later rejoins MUST NOT retain a previously-granted voice
  privilege — an operator MUST grant it again if wanted. The same holds
  symmetrically for operator status (FR-046): rejoining after leaving
  never restores a member's prior operator status either.
- **FR-046**: The server MUST allow a channel operator to grant operator
  status to another member, and to revoke operator status (including
  their own), the same way voice is granted and revoked (FR-045) —
  restricted to operators, the same as every other moderation action
  (FR-013, FR-014) — with one exception: an administrator granting
  themselves operator status via the dedicated self-op command (FR-058)
  does not need to already hold operator status, by design. This is in
  addition to, not a replacement for, first-join-gets-operator (FR-013)
  as how a channel's very first operator is established: it's how
  operator status subsequently spreads to other members. A non-existent
  target, or one who isn't currently a
  member of the channel, MUST be rejected with a clear error rather than
  silently granting a privilege to no one. If the last remaining
  operator revokes their own status, or leaves the channel, without
  first granting operator to another member, the server MUST leave that
  channel without an operator rather than auto-promoting any remaining
  member — the channel continues to exist and function for messaging
  purposes (subject to any moderation flags already set), just with no
  member able to perform operator-gated actions on it, until an
  administrator restores order via FR-057 (force-join) and FR-058
  (self-op).
- **FR-047**: The server MUST allow a channel operator to mark a channel
  private or secret (restricted to operators, the same as every other
  moderation action, FR-013/FR-014), and MUST hide such a channel's
  existence from a non-member: topic-viewing (FR-040), membership listing
  (FR-041), and the active-channel list (FR-042) MUST each treat that
  channel exactly as they would treat one that does not exist for a
  non-member requester, not merely refuse with a distinguishable
  "access denied" response — a non-member MUST NOT be able to tell "this
  channel doesn't exist" apart from "this channel exists but is hidden."
  A channel's own members MUST see it normally, with no restriction. An
  administrator (FR-033) MUST also see it normally regardless of
  membership, the same transparency guarantee FR-032 already gives
  administrators over cloaked hostnames. Private and secret MAY be
  treated identically in this release (both hide equally, the way
  `secret` already unambiguously must); this specification does not
  require distinguishing a softer "listed but obscured" variant some
  historical IRC networks gave private channels.
- **FR-048**: A channel name MUST conform to a defined grammar: a leading
  `#` followed by additional characters, excluding space, comma, and
  control characters, up to the server's configured maximum channel name
  length (FR-056; 50 characters total, including the leading `#`, by
  default) — the same rigor already applied to nickname format (FR-002's
  uniqueness rule is a separate, independent check from this one,
  mirroring how nickname format and nickname uniqueness are independent
  checks). A `JOIN` attempt naming a channel that violates this grammar
  MUST be rejected with a clear, specific error distinct from the
  "nickname in use"-style error FR-002 defines, not silently accepted as
  a new channel's identity.
- **FR-049**: For incoming messages, the server MUST enforce a maximum protocol line length of
  512 bytes (including the trailing CR-LF) for a message's command and
  parameters, plus up to 4096 additional bytes for the message-tags
  section specifically, per the IRCv3 message-tags specification's
  required server-side allowance (FR-025 — this server implements
  message-tags, so this allowance applies). A line exceeding either
  budget MUST be rejected with a specific, actionable error distinct
  from FR-015's other malformed-message cases — not silently truncated,
  partially processed, or lumped in with a generic "malformed command"
  response the sender would have to guess the actual cause of.
  Outgoing messages MUST also fit a 512-byte command section, including
  the sender prefix, separators, and CR-LF, measured in UTF-8 bytes.
  NAMES replies MUST split between complete nickname tokens, preserving
  membership, operator/voice prefixes, and existing end-of-list behavior.
  When adding the sender prefix makes an accepted PRIVMSG or NOTICE too
  long, only its text may be shortened, at a complete Unicode code-point
  boundary; the prefix and target MUST remain intact. Other oversized
  output, including text with no room for a complete character, MUST be
  logged and dropped without stopping subsequent delivery. Outgoing tags
  have a separate 8191-byte allowance, including their leading `@` and
  trailing space, with at most 4094 escaped tag-data bytes from each
  client/server origin. Pre-built raw reply tags are server-originated.
  Tags exceeding these allowances MUST be logged and dropped with the
  message, never truncated. This outbound policy addresses issue #14.
- **FR-050**: The server MUST have an administrator-configurable name,
  used as the source of every server-originated protocol message
  (numeric replies, and any other message whose sender is the server
  itself rather than a client) — the structural counterpart to FR-030's
  client hostmask prefix, but identifying the server, not a user. If the
  administrator has not explicitly configured one, the server MUST fall
  back to a reasonable default rather than sending messages with an
  empty or malformed source (see Assumptions). The server's name MUST
  contain at least one "." character, whether administrator-configured
  or defaulted — an administrator-supplied value without one MUST be
  rejected the same way any other invalid configuration value is
  (FR-012). This is not cosmetic: a nickname (FR-002's grammar) can never
  contain a ".", so a dot-free server name would be indistinguishable
  from a nickname to a client parsing a message's source, exactly the
  ambiguity real IRC clients rely on the "." to resolve.
- **FR-051**: Upon successful registration completion (FR-001), the
  server MUST send the newly registered client a registration-completion
  burst consisting of, in order: a welcome confirmation; the server's own
  identification (its name, FR-050, and software version); the set
  of user-mode and channel-mode letters currently recognized (core plus
  any currently-enabled extension's, FR-043/FR-044's mode mechanism);
  the server's supported-features advertisement (FR-055). Since this
  release implements no message-of-the-day content, the server MUST
  conclude the burst with the standard "no message-of-the-day"
  indication rather than leaving the client to wait indefinitely for a
  burst-completion signal that will never arrive — a client MUST be able
  to treat this indication as "registration burst finished," not just
  RPL_WELCOME alone.
- **FR-052**: Nicknames and channel names MUST be compared
  case-insensitively wherever the server determines whether two names
  are "the same" — uniqueness checks (FR-002, FR-003), and resolving the
  target of any command that names a nickname or channel (e.g.,
  `PRIVMSG`, `WHOIS`, `KICK`, `MODE`, `TOPIC`, `NAMES`) — using IRC's
  traditional casemapping, not simple byte-for-byte comparison: standard
  ASCII letters fold together (`A`-`Z` with `a`-`z`), and in addition
  `[` folds with `{`, `]` with `}`, `\` with `|`, and `^` with `~` (RFC
  2812 §2.2 — IRC's Scandinavian-origin casemapping, still the
  near-universal default across deployed IRC networks today). A client
  registered as "Alice" MUST be reachable via `PRIVMSG alice`, and MUST
  block a second client from also registering "alice" or "ALICE".
- **FR-053**: A numeric reply sent to a session that has not yet
  completed registration (FR-001) — most notably `431`/`432`/`433`, all
  of which can fire while a client is still negotiating its nickname —
  MUST address that reply to `*` (the standard placeholder for "no
  nickname yet"), not an empty value, not a value the client hasn't
  claimed yet, and not omitted entirely.
- **FR-054**: Message text the server interprets as human-readable
  content — `PRIVMSG`/`NOTICE` bodies, channel topics, realnames,
  channel names (FR-048's grammar, further constrained by this
  requirement), and `QUIT`/`PART` reasons (FR-060) — MUST be valid
  UTF-8. The server MUST reject a message containing an invalid UTF-8
  byte sequence in one of these fields as malformed (FR-015), the same
  way any other malformed protocol message is rejected — not pass the
  invalid bytes through unvalidated, mistranscode them, or silently
  discard just the invalid portion while accepting the rest. This does
  not apply to nicknames, which already have their own strict,
  ASCII-only grammar (FR-002) that valid UTF-8 membership doesn't add
  anything to.
- **FR-055**: As part of the registration-completion burst (FR-051), the
  server MUST advertise its feature/limit support to the newly
  registered client via the standard `RPL_ISUPPORT` mechanism (the de
  facto reuse of numeric `005` virtually every deployed IRC server and
  client relies on today, not RFC 2812's original `RPL_BOUNCE` meaning —
  see Assumptions), split across as many lines as needed to respect
  FR-049's line-length limit. At minimum, this release MUST advertise:
  the casemapping in effect (FR-052); which channel-name prefix
  character(s) are recognized (FR-048); the maximum nickname length
  (contracts/irc-protocol-commands.md "Connection Registration
  Grammar"); the maximum channel name length (FR-048); the maximum
  channel topic length (FR-056); the currently-recognized channel-mode
  letters, in the standard grouped-by-parameter-behavior form (FR-043);
  the member-status prefix characters for operator and voice
  (FR-045/FR-046); and that the server enforces UTF-8 for message text
  (FR-054), using the standard `UTF8ONLY` token. A future server
  extension contributing an additional channel-mode flag (FR-043) MUST
  be reflected here the same way it is already reflected in `004`'s
  mode-letter list (FR-051) — one source of truth for which channel-mode
  flags are currently recognized, not two independently-maintained lists
  that could drift apart.
- **FR-056**: The server MUST allow an administrator to configure the
  maximum length of a nickname, a channel name, and a channel topic
  independently, each defaulting to this specification's baseline value
  when not explicitly configured (nickname: 9 characters, FR-002's
  grammar; channel name: 50 characters including the leading `#`,
  FR-048; topic: 390 characters, a widely-used real-world IRC default —
  see Assumptions). Each configured value MUST be a positive integer and
  MUST NOT exceed 400 characters, rejected at load time with the same
  specific, actionable error any other invalid configuration value gets
  (FR-012) if violated — the 400-character ceiling keeps every field
  comfortably within FR-049's 512-byte base line budget once command
  framing (command name, target, sigils, CR-LF) is accounted for,
  regardless of which field is involved. A configured value MUST be
  enforced everywhere the corresponding baseline currently is — nickname
  and channel name length are already part of FR-002's/FR-048's grammar
  checks (`432`/`476`) and simply become parameterized by this
  configuration rather than a fixed constant — and MUST be reflected in
  the `RPL_ISUPPORT` advertisement (FR-055) so a client never has to
  discover the actual enforced limit by trial and error. A channel
  topic-change attempt (FR-040) exceeding the configured topic length is
  a new enforcement case with no prior length check of its own: the
  server MUST reject it with the same `417 ERR_INPUTTOOLONG` error
  FR-049 already uses for an oversized protocol line — the same class of
  "input exceeds a configured length limit" failure — not silently
  truncate it to fit.
- **FR-057**: The server MUST provide a dedicated in-band command,
  restricted to administrator privilege (FR-033), through which an
  administrator joins any channel by name — creating it if it doesn't
  exist (FR-003) or joining an existing one — bypassing every
  currently-active channel-mode flag that gates `JOIN` (FR-043's `gates`
  mechanism). This is a distinct, explicit command from ordinary
  `JOIN`, not a privilege bypass silently applied to `JOIN` itself — an
  administrator's regular `JOIN` remains subject to the same gates as
  anyone else's, so bypass only happens when deliberately invoked. It
  does not bypass the nickname/channel-name grammar (FR-002/FR-048) or
  UTF-8 validity (FR-054) — a malformed channel name is rejected the
  normal way — and it does not itself grant operator status (FR-058 is
  the separate mechanism for that). Two currently-recognized flags
  actually gate `JOIN` and are therefore bypassed by this command: the
  ban list (FR-062) and `invite-only` (FR-065) — this command was wired
  to the same generic `JOIN`-gate check point ordinary `JOIN` uses
  before either flag existed, so both bypasses required no change to
  this command when their flags were added, the same "mechanism exists
  now, extended later without revisiting this command" posture FR-043
  itself establishes for the gate mechanism as a whole.
- **FR-058**: The server MUST provide a dedicated in-band command,
  restricted to administrator privilege (FR-033) and to a sender who is
  currently a member of the target channel, through which an
  administrator grants or revokes channel-operator status on
  themselves specifically — regardless of whether they already hold
  it, and regardless of whether the channel already has one or more
  existing operators (the exception FR-046 itself names). This command
  MUST be scoped to the sender's own membership only — it MUST NOT
  accept or apply to a different target nickname; granting operator
  status to someone else remains exclusively FR-046's mechanism,
  restricted to existing operators. A sender who isn't currently a
  member of the target channel MUST be rejected with the standard
  "not on channel" error rather than being implicitly joined — FR-057's
  force-join command is the separate, explicit step for that.
- **FR-059**: The server MUST assign a unique identifier to every message
  it relays to one or more clients (channel messages, direct messages,
  and other server-relayed events alike), generated by the server
  itself — never derived from message content, never client-supplied.
  Two distinct messages MUST NOT receive the same identifier, and one
  message's identifier MUST be the same value for every recipient of
  that message, the same "computed once, shared across every recipient"
  invariant FR-025's `server-time` timestamp already has. For a
  recipient that has negotiated the `message-tags` capability (FR-025),
  the server MUST include this identifier as the standard `msgid`
  message tag on that message; a recipient that has not negotiated
  `message-tags` MUST NOT receive it, since a tag cannot be sent without
  that capability's framing in the first place. This is not a new,
  independently-toggleable capability of its own — unlike `server-time`,
  which exists as a separate capability specifically because a client
  might reasonably want tag framing without timestamps or vice versa,
  nothing suggests a client would want `message-tags` without message
  identification also being available, so `msgid` is simply part of what
  `message-tags` itself provides once negotiated.
- **FR-060**: The server MUST allow a client to voluntarily end its own
  connection at any time — including before registration completes, not
  only afterward — via a dedicated `QUIT` command, optionally supplying
  a human-readable reason. A voluntary `QUIT` MUST use the same
  cleanup and notification FR-017 already requires for a lost
  connection (channel membership removal, notification to every
  affected channel) — `QUIT` is the client-initiated trigger for that
  cleanup, not a separate mechanism from it, and is the same shared
  cleanup path a keep-alive timeout (FR-039) or an abrupt, unexpected
  disconnect also triggers. The notification sent to affected channels
  MUST always carry a reason: the client-supplied one if `QUIT` gave
  one, or a server-supplied default if it didn't or if the disconnect
  wasn't client-initiated at all (e.g., a keep-alive timeout) — never an
  empty or blank reason, since "no reason given" and "no notification
  content at all" are different things a client shouldn't have to guess
  between. A session that has not yet completed registration MAY still
  send `QUIT` to cleanly close its connection — it simply has no channel
  memberships yet to clean up, not a case this command needs to reject.
- **FR-061**: The server MUST support a user-search command (`WHO`)
  allowing a registered client to query connected users in three forms:
  by channel name, returning that channel's current members (subject to
  the same private/secret visibility restriction FR-047 already applies
  to `TOPIC`/`NAMES`/`LIST` — a non-member/non-administrator querying a
  private or secret channel MUST get the identical "as if it doesn't
  exist" treatment those commands already give); by an exact nickname,
  returning that one user if currently connected; or by a wildcard mask
  pattern (`*`/`?`), returning every currently-connected user whose
  nickname matches — and, with no argument at all, every currently-
  connected user. Each returned entry MUST use the same real-vs-presented
  hostname resolution FR-038 already defines for `WHOIS`. The
  channel-name form MUST NOT be affected by invisibility (FR-044) —
  a channel's membership list is exactly what `NAMES` (FR-041) already
  shows to anyone with visibility into that channel, and `WHO` on the
  same channel MUST NOT show a different membership than `NAMES` does.
  The exact-nickname and mask forms, by contrast, MUST exclude a user
  currently holding the `invisible` user mode (FR-044) from the results
  unless the requester shares at least one channel with them or holds
  administrator privilege — administrators, and any requester who
  shares a channel with an invisible user, MUST see that user normally,
  the same transparency guarantee FR-032/FR-047 already establish for
  other privacy-affecting cases elsewhere in this specification. This
  exclusion exists specifically to keep invisibility meaningful against
  enumeration/search (the mask and no-argument forms) and against being
  found by a guessed or otherwise-learned nickname outside any shared
  context (the exact-nickname form) — not to hide a user from people who
  can already see them in a channel they share. The server MUST also
  allow an administrator to configure whether the wildcard-mask and
  no-argument forms are available to non-administrator clients at all,
  independent of the `invisible`-based exclusion above; when configured
  off, a non-administrator's wildcard-mask or no-argument `WHO` MUST
  return an empty result (a bare "end of list" completion, indistinguishable
  from a real search that happened to match nobody) rather than an error
  — a client MUST NOT be able to tell "the server found nothing" apart
  from "this form of search is disabled here." An administrator MUST be
  able to use both forms normally regardless of this configuration —
  this setting restricts non-administrator search capability, not
  administrator visibility, which FR-032/FR-047's transparency guarantee
  already covers. The channel-name and exact-nickname forms MUST NOT be
  affected by this configuration at all; only the two forms capable of
  broad enumeration are.
- **FR-062**: The server MUST allow a channel operator to maintain a
  per-channel ban list of nickmask patterns (`nick!user@host`, with
  `*`/`?` wildcards in any segment) — adding a mask, removing a
  specific mask, and listing every currently-active mask, restricted to
  operators the same as every other moderation action (FR-013, FR-014).
  A mask MUST be matched against a client's *presented* identity (the
  same `nickname!ident@hostname` form its message hostmask already
  shows, FR-030/FR-031) **and**, independently, against its real,
  unobfuscated hostname/IP (FR-032) in the same `nickname!ident@hostname`
  shape — a match against either MUST cause the ban to apply. This MUST
  hold regardless of which channel operator added the mask or whether
  they themselves can see the client's real hostname/IP (FR-032's
  restriction on *viewing* a real hostname/IP is unrelated to and
  unaffected by this — an operator specifying a mask that happens to
  match a client's real value still never gets to see that value, only
  whether their supplied pattern currently matches something). This
  keeps a ban resistant to evasion via a hostname-obfuscation extension
  (FR-031): a client cannot escape a mask targeting their real,
  underlying network identity merely because a cloaking extension
  presents a different value to other clients. An operator supplying a
  partial mask (missing
  the `user` and/or `host` segment) MUST have the missing segment(s)
  filled with a wildcard automatically, matching standard IRC ban-mask
  convention. A currently-active ban MUST have two effects: (1) a client
  already in the channel whose presented identity or real hostname/IP
  matches it MUST be prevented from sending further channel messages to
  it — muted, not removed from the channel — until the matching ban is
  lifted or neither of their identities matches it any longer; (2) a
  client not currently in the channel whose presented identity or real
  hostname/IP matches it MUST have a `JOIN` attempt on that channel
  rejected. Adding a mask already
  present, or removing one not present, MUST be treated as a harmless
  no-op, not an error — the same idempotent-change posture this
  specification already applies to every other mode change (FR-043).
  The server MUST enforce a maximum of 100 active bans per channel and
  reject an attempt to add a mask beyond that limit with a clear,
  specific error distinct from every other rejection this requirement
  defines — an unbounded, operator-populated list is the kind of
  unbounded growth this specification consistently guards against
  elsewhere (e.g., FR-016's rate limiting, FR-049's line-length limit).
  Unlike FR-016's rate-limiting thresholds or FR-039's keep-alive
  interval (administrator-configurable since 009-connection-monitoring-log),
  this specific ceiling is a fixed value, not administrator-configurable
  — see Assumptions for why a ban list gets a hard number where those
  others don't. The operator MUST also be able to maintain two exception
  lists: `e` (ban exceptions) and `I` (invite exceptions), using the same
  partial-mask normalization, casefolding and matching rules as `b`, with
  independent 100-entry caps. A matching `e` mask against either presented
  or real identity exempts that identity from `b` for both JOIN and channel
  SEND. A matching `I` mask bypasses `invite-only` (`+i`) only; it MUST NOT
  bypass a channel key (`+k`) or user limit (`+l`). Existing JOIN/SEND
  latency budgets remain unchanged; each relevant list scan is bounded to
  100 masks. `MODE <channel> e`/`I` list queries use `348`/`349` and
  `346`/`347`, respectively. ISUPPORT advertises `EXCEPTS=e`, `INVEX=I`,
  `MAXLIST=Ibe:100`, and catalog-derived `CHANMODES=Ibe,k,l,imnpst`.
- **FR-064**: The server's channel `MODE` command MUST accept multiple
  mode-flag changes in a single invocation — multiple letters after one
  sign (e.g., `+bbb` naming three ban masks), multiple signed groups in
  the same command (e.g., `+o-v`), and any mix of flag kinds together —
  not only the single-flag-per-invocation shape earlier requirements in
  this specification illustrated with. Each flag that requires a
  parameter (a `MEMBER`-kind target, FR-045/FR-046, or a `LIST`-kind
  mask, FR-062) MUST consume the next not-yet-used parameter supplied
  with the command, in the same left-to-right order the flags
  themselves appear in. Flags MUST be applied in that same order,
  each independently — a later flag's rejection (e.g., an unknown mode
  letter, or a target that isn't a current member) MUST NOT undo an
  earlier flag's already-applied change within the same command, and
  processing MUST stop at the first such rejection rather than skipping
  it and continuing with later flags. The server MUST allow an
  administrator to configure a maximum number of parameter-consuming
  flags accepted in a single command; if a command's flags would exceed
  it, the server MUST apply flags up to that limit, in order, and MUST
  NOT apply anything beyond it — this specific limit MUST NOT produce
  an error reply (see Assumptions for why this is a deliberate exception
  to this specification's usual "reject explicitly" posture elsewhere).
  The confirmation this specification already requires echoing to
  channel members for a mode change MUST reflect only the flags
  actually applied, never the full originally-requested set when the
  two differ.
- **FR-065**: The server MUST implement channel invitations as core,
  always-present behavior (FR-036) — the same tier as every other
  currently-implemented channel-mode flag (FR-013, FR-045/FR-046,
  FR-047, FR-062), not an optional extension. This is one feature with
  two parts, matching RFC 2812 §3.2.7's `INVITE` command and §4.2.3's
  `invite-only` channel-mode flag as a paired mechanism, not two
  independent ones: (1) any current member of a channel MUST be able
  to invite another connected client to it by nickname, recording the
  invitation against the target's nickname; if `invite-only` is
  currently active on that channel, only a channel operator MAY issue
  the invitation — an ordinary member's attempt MUST be rejected with
  the same operator-required error FR-014's other operator-gated
  actions use. Naming a client already a member of the channel, or a
  nickname that isn't currently connected, MUST each be rejected with
  its own distinct, specific error rather than silently accepted or
  merged into one generic failure. (2) An operator MUST be able to set
  or clear `invite-only` on their channel, the same restricted-to-
  operators posture FR-013's other flags already use; while active, an
  otherwise-eligible `JOIN` MUST be rejected unless the joiner
  currently holds a matching invitation on that channel — evaluated as
  an independent condition alongside every other currently-recognized
  `JOIN`-gating flag (FR-043), including FR-062's ban list: holding a
  valid invitation bypasses only the `invite-only` restriction, never
  a ban targeting the same client. A recorded invitation MUST be
  consumed (no longer usable for a future `JOIN`) the moment its
  target successfully joins that channel, regardless of whether
  `invite-only` was the reason the join needed one; it MUST also be
  cleared, along with every other per-channel state, when the channel
  is recreated from zero members (FR-003). The server places no other
  automatic expiration on a recorded invitation, and does not track or
  transfer it if its target later changes nickname before joining — an
  invitation is a nickname-keyed record, the same treatment FR-062's
  ban entries already give the operator identity that created them.
- **FR-066**: The server MUST provide an extension point through which a
  future optional extension can reject an incoming connection whose
  host/IP matches an administrator-defined network mask — closing the
  connection before it can register (FR-001), the same kind of
  pre-registration block classic IRC networks call a "G-line," and
  distinct from FR-062's per-channel ban-mask, which only restricts a
  client's standing within one channel (`SEND`/`JOIN`) and never closes
  the underlying connection. Implementing this rejection capability
  itself — the actual mask matching, the blocked-mask list and its
  storage, the administrative interface used to manage it, and any
  expiration policy — is deferred and out of scope for the initial
  release; no such extension ships, and no administrator-facing command
  exists yet to manage one. What this release commits to now is
  narrower: the connection-acceptance path (the point at which the
  server decides whether a newly-connected client may proceed toward
  registration) MUST be structured so that capability can be added
  later purely as an optional extension (FR-011's existing
  enable/disable/no-restart model), without requiring a redesign of
  core connection-handling logic — the same "the mechanism exists now,
  empty of any concrete rule, extended later without a core change"
  posture FR-043 already establishes for channel-mode flags.

### Key Entities

- **Client Session**: A single connected client's live state — its
  network connection, registration status, negotiated capabilities,
  authentication status, and channel memberships.
- **User Identity**: The persistent, human-recognizable identity a client
  presents (nickname, and optionally a verified account), independent of
  any single connection. Presented on the wire in the standard
  `nickname!ident@hostname` form (FR-030). The `hostname` portion MAY be
  obfuscated for other clients by an optional cloaking extension (FR-031),
  but the real value is always retained internally and remains visible to
  administrators (FR-032) and to the user-lookup command (FR-037/FR-038)
  when a client looks itself up or an administrator looks anyone up —
  never to a non-administrator client looking up someone else.
- **Administrator Privilege**: A server-wide grant on a Client Session,
  obtained via FR-034's in-band credential command, that authorizes
  FR-032's administrative commands. Distinct from channel-operator status
  (Channel entity) and independent of the deferred Account entity.
- **Account** *(deferred with Story 3 — not present in the initial
  release)*: A registered identity managed by the account module
  (built-in or external), distinct from a nickname — one account may be
  associated with a nickname registration and/or one or more registered
  channels, and persists independently of any single connection or
  session.
- **Channel**: A named, joinable group through which multiple clients
  exchange messages; tracks its membership list, operator/privilege
  assignments, moderation settings, and an optional topic (FR-040) — set
  only by an operator, visible to any client (FR-040/FR-041), unlike
  moderation settings which govern who may send. In a later release, once the
  account module exists, a channel MAY be registered to an Account, in
  which case its operator/privilege assignments are sourced from that
  account's access records (FR-026) instead of join order; the initial
  release has no such registration.
- **Capability**: A named, independently negotiable protocol enhancement
  that a client may request; has an availability state (offered/not
  offered) determined by which extensions are currently enabled.
- **Extension**: An independently enableable/disableable unit of optional
  server functionality (e.g., an individual capability, a command set, a
  channel/user-mode flag an extension chooses to contribute, FR-043/
  FR-044, or — once one is eventually built — a connection-admission
  rule rejecting matching connections by network mask, FR-066)
  configured by the administrator. Core protocol behavior —
  every currently-implemented channel-mode flag (FR-013, FR-045/FR-046,
  FR-047, FR-062, FR-065) and the capability-negotiation mechanism
  itself (FR-035) — is never modeled as an Extension; it is always
  present, the same tier as any other core command (FR-036). The
  mechanism, not any particular flag, is what determines core-vs-
  extension status: FR-043's extension point exists so a flag *can* be
  contributed by an optional extension, not so every new flag *must*
  be — a flag a release's actual stories need (as `invite-only`,
  FR-065, turned out to be) is added as core the same way `ban-mask`
  (FR-062) was, without that decision being forced by the mechanism
  itself.
- **Server Configuration**: The administrator-controlled settings
  determining which extensions are active and how core and optional
  behavior is tuned — including the server's own name (FR-050), used as
  the source of every server-originated message, defaulting to the
  deployment host's network hostname if unconfigured.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A new user can go from initial connection to successfully
  sending their first channel message in under 10 seconds using any
  standard IRC client.
- **SC-002**: Messages sent to a channel are delivered to all other
  members of that channel within 1 second under normal operating
  conditions.
- **SC-003**: The server sustains at least 1,000 simultaneously connected
  clients without message delivery delay exceeding the 1-second target in
  SC-002.
- **SC-004**: A capability-aware client can complete capability discovery
  and negotiation in a single round trip before registration completes.
- **SC-005**: An administrator can change which optional extensions are
  active using configuration alone — no code changes, rebuild, or server
  restart — and observe the effect reflected for connected and new
  clients within 1 minute of applying the change.
- **SC-006**: During a sustained burst of excessive traffic from a single
  connection, message delivery latency for all other, well-behaved
  clients does not increase beyond the SC-002 target.
- **SC-007** *(Deferred with Story 3 — not an initial-release gate)*: 100%
  of authentication attempts with valid credentials succeed, and 100% of
  attempts with invalid credentials are rejected, across repeated
  testing.
- **SC-008**: Administrators can identify the cause of a configuration
  error from the reported error message alone, without consulting source
  code, in under 5 minutes.
- **SC-009**: An authorized administrator can gain administrator privilege
  and enable/disable an extension entirely through in-band IRC client
  commands — no file system or configuration-file access to the server
  host required — with the same effect and timing as SC-005.
- **SC-010**: Across repeated testing, 100% of non-administrator lookups
  of a *different* client's information return that client's presented
  hostname, never its real hostname/IP; 100% of self-lookups and
  administrator lookups return the real hostname/IP.

## Assumptions

- The implementation platform is Java, per explicit stakeholder direction;
  specific frameworks, libraries, and build tooling are determined during
  technical planning and are out of scope for this specification.
- "Modular" is interpreted as: optional functionality — individual
  extensions (capabilities, command sets) — can be independently enabled,
  disabled, and configured by an administrator without modifying core
  server code and without restarting the running server process (FR-011).
  Channel moderation and the capability-negotiation mechanism are
  explicitly core, always-present behavior, not part of this optional-
  extension surface (FR-035, FR-036). This applies to toggling existing
  extensions on/off; whether entirely new, third-party extensions can be
  installed at runtime without a restart is not required by this
  specification and is a planning-phase design choice.
- Authentication (Story 3, FR-009/FR-010) and the account module
  (FR-023/FR-024) are deferred and not required for the first iteration;
  when they are implemented, authentication remains optional per-connection
  by default, and an administrator MAY configure the server to require it
  as a configuration concern rather than a change to FR-009 itself.
- The optional TLS connection offering (FR-018) uses standard transport
  encryption; certificate management approach is a planning concern, not
  a specification concern.
- Rate limiting/flood protection (FR-016, SC-006) uses reasonable
  industry-standard defaults, configurable by the administrator, rather
  than a single fixed universal threshold.
- Connection keep-alive (FR-039) originally used reasonable
  industry-standard probe-interval and response-timeout defaults with
  neither exposed as an administrator-configurable Server Configuration
  setting. As of 009-connection-monitoring-log, the probe interval
  (how long a connection may sit idle before being probed) is
  administrator-configurable (`keepAliveFrequencySeconds`, default 120
  seconds, contracts/`009-connection-monitoring-log`/
  server-configuration-extensions.md) — the same tunability posture
  FR-016's rate-limiting thresholds already had. The response timeout
  and the internal check cadence remain fixed, non-configurable
  implementation details.
- The server does not enforce its own maximum-connections cap or send a
  dedicated capacity-exceeded rejection at the protocol level; SC-003's
  1,000-connection floor is a sustained-operation target the server MUST
  meet, not a ceiling with special in-protocol handling once exceeded.
  Capacity beyond what's actually sustained is bounded by OS/network
  resources and whatever an administrator configures at the deployment
  layer (e.g., a reverse proxy, container resource limits), not by this
  specification.
- If the administrator does not configure a server name (FR-050), the
  server falls back to the deployment host's own network hostname rather
  than an empty or placeholder value — a reasonable, zero-configuration
  default consistent with how most real IRC servers behave, not a value
  requiring administrator action before the server can start. If that
  hostname itself doesn't contain a "." (common for a bare local/
  container hostname with no domain suffix), the server appends a fixed,
  clearly-synthetic suffix (e.g., `.local`) so FR-050's dot requirement
  holds even in the zero-configuration case, rather than only being
  enforced against explicit administrator input. The server's software
  version (used alongside the name, FR-051) is derived from the
  build/release itself, not administrator-configured — sourced from the
  build system's own version identifier at build time, never entered or
  overridden separately.
- FR-055's `RPL_ISUPPORT` advertisement is deliberately a fixed,
  minimal set — only tokens this specification already has a concrete,
  decided answer for. Tokens some real networks advertise but this
  release has no defined limit or behavior for (e.g., a per-client
  channel-join limit, a network name distinct from FR-050's server name,
  multi-target `PRIVMSG`) are omitted rather than advertised with an
  invented value — an absent token is the standard, correct way to say
  "unspecified," not a gap to fill with a guess.
- FR-056's topic-length default (390 characters) is not an RFC value —
  RFC 2812 defines no topic length at all — but a widely-used real-world
  IRC default (several deployed networks converge on it), chosen over
  inventing an arbitrary number of this specification's own. The shared
  400-character ceiling on all three configurable lengths (nickname,
  channel name, topic) exists purely to keep configuration mistakes from
  producing a value that can never fit a valid protocol line under
  FR-049's 512-byte budget — it is a safety bound, not a target value
  administrators are expected to configure up to.
- Channel modes beyond moderated-mode and members-only (FR-013/FR-043),
  and user modes beyond `operator` (FR-044), are recognized at the
  wire-protocol level — a future client library can still parse them
  from any server — but their behavior is deferred past this release,
  the same treatment already given to `AUTHENTICATE`/SASL (Story 3) and
  `NickServ`/`ChanServ`-style commands. This is a scope boundary, not an
  oversight: FR-013's two implemented channel flags and FR-044's one
  implemented user flag are what this release's actual stories need
  (Story 5's moderation; administrator visibility, this change), and
  nothing in this release depends on any other mode existing.
  Unlike those other deferrals, though, FR-043/FR-044 also commit to *how*
  a later mode gets added: via an optional server extension contributing
  it (research.md "Channel/user mode extensibility"), not a core-codebase
  change — so a later release (or a third-party extension) can add, say,
  a registered-channel/user flag once account registration exists,
  without this specification needing to be revisited first. That
  commitment covers simple on/off flags only. Standard IRC's
  value-carrying (e.g. a numeric limit) and list-type (e.g. a mask list)
  channel modes are cataloged for reference (contracts/
  irc-protocol-commands.md "Full Channel Mode Catalog") but have no
  extension mechanism defined yet — deliberately: no extension needs one,
  and guessing the right shape without a concrete consumer risks building
  the wrong thing.
- No mobile app, web client, or GUI is in scope — this specification
  covers the server and its protocol behavior only; any client software
  is out of scope.
- Server-to-server federation is deferred past initial release (see
  FR-021). It is not assumed to fit Story 4's client-facing extension
  system (individual capabilities, command sets) — federation's
  server-to-server trust boundary and distributed state make it a
  different kind of extension, whose own mechanism is a planning-phase
  decision to be made when federation is actually scoped. What this
  specification does commit to now is narrower: the specific core
  behaviors named in FR-022 (nickname/channel uniqueness scope, channel
  message delivery, connection-loss handling) must be implemented so a
  later federation effort doesn't require redesigning them, plus two
  standing constraints on that future effort itself — extension
  consistency across linked servers (FR-028) and a single authoritative
  account source network-wide (FR-029).
- Administrator-defined connection-level network-mask blocking
  ("G-line"-style, FR-066) is deferred past this release the same way
  federation (FR-021) and Story 3's `AUTHENTICATE`/SASL are — but unlike
  federation, this specification does not leave FR-066's extension
  mechanism itself as an open planning-phase question: the existing
  optional-extension model (FR-011) is already a structural fit — an
  extension that rejects a connection is architecturally the same shape
  as `cloak` (FR-031) supplying a hostname-display transform or `admin`
  (FR-032) supplying administrative commands, just applied at connection
  acceptance instead of message display or command dispatch. What
  remains a genuine planning-phase decision, deferred to when this
  extension is actually built, is everything about the rejection rule
  itself: mask storage/persistence, the administrative interface used to
  manage it, any expiration policy, and whether a match is judged by
  real host/IP alone or also against a cloaked presented value (see
  Edge Cases) — none of which this specification invents an answer for
  now, the same "don't guess the shape without a concrete consumer"
  discipline already applied to `VALUE`/`LIST`-kind channel modes beyond
  `ban-mask` (see the channel-mode deferral bullet above).
- The `ident` portion of FR-030's `nickname!ident@hostname` identity is
  derived from the username the client supplies at registration (FR-001),
  not from an RFC 1413 IDENT-protocol lookup against the client's host —
  IDENT-protocol verification is out of scope for this release.
- FR-034's administrator-privilege mechanism intentionally mirrors the
  classic IRC OPER pattern (credentials defined in server-side
  configuration, verified in-band) specifically because it must not depend
  on the deferred account module; if Story 3 is later implemented, whether
  administrator privilege should additionally support account-based
  grants is a decision for that future work, not this release.
- FR-061's non-administrator mask/no-argument `WHO` restriction defaults
  to enabled (available to everyone) rather than disabled — the
  `invisible` user mode (FR-044) is this release's primary, per-user
  answer to enumeration concerns; this server-wide setting is an
  additional, coarser lever for deployments that want to restrict
  search capability entirely, not the default posture, matching this
  project's general "permissive zero-configuration default, restrict
  explicitly if needed" pattern (e.g., TLS offered but not mandatory,
  FR-018).
- FR-062's ban list blocking both sending and joining is not this
  specification inventing behavior beyond IRC's own ban-mode definition:
  RFC 1459/2811's original wording only describes blocking future joins,
  but the modern IRC client protocol specification — the maintained,
  current documentation those frozen RFCs no longer track — defines the
  ban-mask channel mode as controlling masks "banned from joining or
  speaking in the channel." Gating both `SEND` and `JOIN` is therefore
  the specification-aligned reading of ban-mask, not a stricter,
  project-specific extension of it.
- FR-064's multi-flag `MODE` grouping is not this specification inventing
  new command syntax: RFC 2812 §3.2.3 already defines the `MODE` command's
  grammar as `*( ( "-" / "+" ) *<modes> *<modeparam> )` — a repeatable
  sequence of signed groups, each carrying zero or more mode letters and
  the parameters they need — the same shape every deployed IRC server
  already implements. What the base RFC leaves server-defined is the cap
  on how many parameter-consuming changes one command may carry; this is
  conventionally advertised via the `MODES` token in `RPL_ISUPPORT`
  (FR-055), which this project already sends (previously a fixed `1`,
  now sourced from the new configurable limit). This project defaults
  that limit to `6`, a long-established convention among deployed IRC
  server implementations, administrator-configurable like FR-056's other
  operational limits, bounded to a fixed ceiling of `20` to catch a
  clearly-mistaken configuration value rather than for any specific
  exploit concern (FR-049's line-length limit already bounds what a
  single command can carry regardless of this setting). FR-064's "no
  error reply when the parameter-consuming cap is exceeded" is a
  deliberate, narrow exception to this specification's usual posture:
  every other size/count limit in this specification (FR-049, FR-056,
  FR-062's ban-list cap) rejects with a specific numeral because a
  fitting one exists; no numeral in the RFC 1459/2812 catalog, nor any
  later widely-adopted addition (the way `417`/`474`/`478` are), covers
  "too many parameter-consuming mode changes in one command" — inventing
  one here would repeat the mistake this project has deliberately never
  made elsewhere (research.md "Wire-protocol command & numeric
  completeness"). The truncated `MODE` echo itself — reflecting only
  what was actually applied — is the feedback a client receives instead.
- FR-065 (channel invitations) reverses a placement this specification
  had committed to twice before it was ever built: `invite-only` was
  originally used, in two separate places (FR-043's example and the
  Extension Key Entity's description), as the worked example of a
  future flag a `ServerExtension` would contribute, the same tier as
  `cloak`/`admin`. Once actually scoped, that placement didn't survive
  contact with this specification's own precedent: every other
  currently-implemented channel-mode flag — `moderated`/`members-only`
  (FR-013), `private`/`secret` (FR-047), `voice`/`operator` (FR-045/
  FR-046), `ban-mask` (FR-062) — is core, unconditional behavior, never
  gated by an administrator's extension toggle; only genuinely optional,
  cross-cutting capabilities (`cloak`'s hostname obfuscation, `admin`'s
  privileged command set) are modeled as extensions. `invite-only` and
  its paired `INVITE` command fit the first category, not the second —
  RFC 2812 defines both in its base command/mode sections (§3.2.7,
  §4.2.3), the same tier `KICK` (§3.2.8) already occupies as core, not
  an add-on a deployment might reasonably run without. The two earlier
  "future extension" mentions were about validating that the `gates`
  mechanism itself could support a `JOIN`-gating flag without a core
  code change — a claim about the *mechanism*, which still holds
  regardless of whether the specific flag exercising it turns out to
  be core- or extension-defined.
- FR-065's invitation records deliberately carry no expiration timeout
  and no cap on how many a channel may accumulate, unlike FR-062's
  ban list (a maximum of 100 entries, `478 ERR_BANLISTFULL`). A ban list
  needed an explicit cap because RFC 1459/2812 already defines a
  fitting numeral family for list-limit rejections and unbounded growth
  there is administrator-visible, standing state; an invitation is
  consumed on the target's next successful `JOIN` (or discarded
  wholesale when the channel is recreated from zero members), so its
  worst case is transient, self-bounding churn rather than accumulation
  — and no RFC/de-facto numeral exists for "too many outstanding
  invitations" to reject with in the first place. Inventing a cap and
  a numeral for it here would repeat the exact mistake this
  specification has deliberately avoided everywhere else (FR-064's
  Assumptions entry above); ordinary flood/rate-limiting (FR-016)
  already bounds how quickly one member can issue invitations, the same
  general-purpose safeguard that already covers every other
  high-frequency command in this specification. FR-062's 100-entry
  ceiling is deliberately a fixed value rather than an
  administrator-configurable Server Configuration setting like FR-016's
  rate-limit thresholds, FR-056's length limits, or FR-064's
  parameter-consuming-flags cap: those all trade off differently across
  deployments (a busy public network vs. a small private one legitimately
  want different values), whereas 100 active bans is already generous
  headroom for a single channel's moderation needs regardless of
  deployment size, and RFC 1459/2812's `478 ERR_BANLISTFULL` numeral
  carries no administrator-tunable-limit convention the way `417
  ERR_INPUTTOOLONG` does for FR-056/FR-064's configurable limits — adding
  configurability here would be complexity without a concrete deployment
  need behind it (constitution Development Workflow's "no speculative
  generality" guidance).
