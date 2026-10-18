# Feature Specification: Outbound Queue Overflow Policy

**Feature Branch**: `013-outbound-sendq-policy`

**Created**: 2026-10-18

**Status**: Draft

**Input**: User description: "GitHub issue #50 'Handle outbound-queue overflow during large-channel
JOIN storms': decide, document and implement how the server behaves when a burst of channel
traffic overflows a member's outbound queue. 2,000 clients JOINing one channel at once never
completes, because each member's fixed 256-entry queue overflows and the server silently
disconnects them. Options from the issue: (1) size the queue in bytes and make it configurable,
(2) send an ERROR line before closing, (3) lower fan-out pressure, (4) count overflow
disconnects in the connection monitoring log."

## Context

Every connection has a bounded outbound queue. A sender never waits for a slow recipient: when
a recipient's queue is full the server closes that recipient's connection (the documented
protection against unbounded memory per connection). That behavior stays. What changes is that
the limit becomes a deliberate, documented, configurable policy instead of an implementation
constant, and that an overflow becomes visible to the affected client and to operators.

Today the limit is 256 queued messages, whatever their size. Every JOIN is sent to every current
member of the channel, and every joiner also receives the member listing over many lines. In a
large channel a burst of JOINs therefore pushes one line per joiner into every member's queue
faster than a busy reader drains it. Measured on `main` with clients that keep reading
continuously (issue #48 JOIN-storm test):

- 1,000 simultaneous JOINs: 8 clients disconnected before their own end-of-NAMES.
- Waves of 250: only 999 and 1,491 of 2,000 clients completed.
- Waves of 100: all completed.

So an ordinary event (a channel announced elsewhere, a netsplit-style rejoin) can disconnect
healthy members, and the disconnected client gets no explanation: its connection is simply
closed. Operators see a disconnect with the generic reason "Excess Flood" and cannot tell a
queue overflow from a rate-limit flood.

The audience is twofold: IRC users and bots in large channels, who must not be dropped by a
legitimate burst, and operators, who must be able to size the limit and see when it is hit.

## Clarifications

### Session 2026-10-18

- Q: Which of the issue's four options are in scope? → A: Options 1 (byte-sized, configurable
  limit), 2 (`ERROR` before close) and 4 (log and count overflows). Option 3 (reducing JOIN
  fan-out) is out of scope (FR-013).
- Q: Server-wide limit or per connection class/listener? → A: One server-wide limit
  (`sendqLimitBytes`); the server has no connection classes (FR-005).
- Q: Which storm size is the agreed target? → A: 2,000 simultaneous JOINs on one channel, with the
  default at least 3x the measured per-member volume (FR-003, SC-001).
- Q: Is discarding queued-but-unwritten lines acceptable when a connection overflows, so the
  `ERROR` line is sent first? → A: Yes (FR-007).
- Bounds (user decision): default 512 KiB (confirmed by T003: peak 31,286 B, 3x = 93,858 B),
  minimum 16 KiB, ceiling 2 MiB (2,097,152 bytes), in line with classic
  servers (FR-004, FR-012).

### Follow-up decisions 2026-10-18

- Q: How are deferred capability tags charged? → A: Remove the fixed allowance. Compute each
  enabled capability's contributed escaped tag bytes once per message, memoized by capability;
  `contributeTags` is a pure function of that message (`msgid` uses `messageId`, `time` uses
  `sentAt`). At enqueue charge the recipient's component bytes, forwarded client `+` tags only
  when message-tags is negotiated, contributions of its negotiated enabled capabilities, tag
  delimiters and CRLF. Rendering stays at drain time. Accounting is a **conservative upper
  bound**: it includes actual selected contributions, but may count entries later overwritten
  during tag merging or text later truncated. No fixed allowance or smaller legal tag budget.
  Contract documentation and tests cover purity, memoization, escaped bytes and rendered-wire
  comparison, including legal 4,094-byte server tag data.
- Q: Must the single-wave storm satisfy the existing latency budget? → A: Keep
  `JoinStormLoadTest` at waves of 100 with all its current bounds unchanged. Add a separate
  load-tagged 2,000-client single-wave survival test with zero overflow disconnects and all
  clients completing end-of-NAMES; it has no latency assertion. Finite failure/cleanup timeouts
  remain. The build's integration `loadTest` task may use a documented 2 GiB heap: the T003
  probe exhausts 512 MiB, but reaches all 2,000 clients with 2 GiB. This does not relax the
  wave-of-100 test's latency bounds.
- T003 is complete: measured peak 31,286 B, 3x = 93,858 B; retain the 512 KiB default.
  The probe's old allowance and its failed latency assertion remain historical evidence in
  `baseline.md`, not requirements for the revised estimator or a passing single-wave test.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A JOIN storm no longer disconnects healthy members (Priority: P1)

A large channel is announced elsewhere and 2,000 clients join it within the same second. Every
client that is reading its connection continuously stays connected and receives its own
end-of-NAMES; nobody already in the channel is disconnected along the way.

**Why this priority**: This is the user-visible failure the issue reports; the other stories
are about explaining and observing the limit, which only matters once the limit is sane.

**Independent Test**: Run the separate single-wave survival load test with 2,000 clients and
assert every client reaches its own end-of-NAMES with zero overflow or unexpected disconnects.
Run the unchanged wave-of-100 `JoinStormLoadTest` separately for latency regression coverage.

**Acceptance Scenarios**:

1. **Given** a channel receiving a simultaneous JOIN storm of the agreed size from clients that
   read continuously, **When** the storm runs, **Then** no client is disconnected for outbound
   queue overflow and every client receives its own end-of-NAMES.
2. **Given** the existing 2,000-client storm in waves of 100, **When** it runs, **Then** completion
   time and unrelated-channel latency stay within its unchanged budgets. The separate
   single-wave survival test imposes no latency assertion.
3. **Given** a server with default settings and no configuration changes, **When** the storm
   runs, **Then** scenario 1 holds (the default is sized for the agreed storm).

---

### User Story 2 - A client that really stops reading is still disconnected, and told why (Priority: P1)

A client connects, joins a busy channel and then stops reading its socket entirely. Traffic
destined for it piles up until the configured limit is reached. The server closes that
connection so memory stays bounded, and, where the connection can still take a write, the
client's last line from the server says why.

**Why this priority**: Without this the memory protection that justifies the limit is untested,
and the new, larger limit would be unproven against the case it exists for.

**Independent Test**: Connect a client that never reads, drive channel traffic at it, and assert
it is disconnected before the amount of data queued for it exceeds the configured limit by more
than one message, and that the server stays responsive to everyone else.

**Acceptance Scenarios**:

1. **Given** a connected client that never reads, **When** more data is queued for it than the
   limit allows, **Then** the server closes its connection.
2. **Given** that overflow, **When** the connection is closed, **Then** the client is first sent
   an `ERROR` line naming the sendq limit as the reason, on a best-effort basis (a connection
   whose socket cannot accept the write is closed without it).
3. **Given** that overflow, **When** other channel members are notified of the departure, **Then**
   the QUIT reason they see identifies the sendq overflow rather than a generic flood.
4. **Given** one client that never reads, **When** it overflows and is closed, **Then** no other
   connection is delayed or disconnected as a result.

---

### User Story 3 - Operators can size and observe the limit (Priority: P2)

An operator running a server with large channels wants to change the per-connection outbound
limit without rebuilding the server, and to see in the logs which disconnects were caused by
overflow, with enough detail to tell whether the limit is too low or a client is stuck.

**Why this priority**: The default may not suit every deployment, and a limit nobody can
observe is the situation the issue complains about. It follows the P1 stories because the
default alone fixes the reported failure.

**Independent Test**: Start the server with a configured limit, overflow one connection, and
check the monitoring log for a distinct overflow entry; start it with an invalid limit and check
it refuses to start with an error naming the setting.

**Acceptance Scenarios**:

1. **Given** no limit configured, **When** the server starts, **Then** it uses the documented
   default.
2. **Given** a limit configured in the server configuration, **When** a connection is opened,
   **Then** that connection's outbound limit is the configured one.
3. **Given** a zero, negative, non-numeric or above-ceiling limit, **When** the server starts or
   the configuration is reloaded, **Then** it is rejected with an error identifying the setting;
   on reload the previous configuration stays active.
4. **Given** a connection closed for overflow, **When** the operator reads the connection
   monitoring log, **Then** the disconnect entry carries a reason that distinguishes sendq
   overflow from other causes, and the overflow is also counted so that a rate can be derived.
5. **Given** an operator looking for the policy, **When** they read the server documentation,
   **Then** it states the default size, the unit, how to configure it and the exact client-visible
   message on overflow.

---

### Edge Cases

- A message larger than the whole limit (for example a maximum-length line with a large tag
  set) arrives at an empty queue: the system MUST define whether it is accepted or counts as
  overflow, and a lower limit than one maximum-size line MUST be rejected at configuration time.
- Several overflow triggers race for one connection (queue full on two senders at once, or
  overflow racing a client QUIT): the connection MUST be closed once, with one disconnect log
  entry and one QUIT broadcast.
- The queue is full at the moment the explanatory `ERROR` line would be written: the line is
  either delivered ahead of closing, or dropped, but the close MUST NOT wait on the slow client
  and MUST NOT grow the queue past the limit.
- The limit is lowered on reload while connections hold more queued data than the new limit:
  existing connections MUST keep working; the new limit applies to connections opened after the
  reload (or the system states otherwise).
- A server-initiated closing sequence (for example `KILL`, or a QUIT acknowledgement) queues its
  final lines on a queue that is already near the limit: those lines MUST NOT be turned into an
  overflow disconnect by the limit change.
- Many connections overflow at the same instant: each is closed independently and the log
  carries one entry per connection, not one per dropped message.
- Per-connection memory: with the default limit and the expected connection count, the worst case
  of all connections being stuck at the limit MUST be documented so operators can size it.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The limit on data queued for one connection MUST be measured in bytes of outbound
  protocol data, not in number of messages, so that the policy does not depend on how a burst is
  split into lines. Charging MUST upper-bound the rendered outbound wire bytes, including escaped
  tag values, all delimiters and CRLF, without serializing per recipient at enqueue. Each
  recipient is charged only for the client tags and enabled capability contributions selected
  for it; contribution byte metadata is memoized once per message/capability. Tag collisions and
  text truncation may make the bound conservative; a fixed tag allowance is prohibited.
- **FR-002**: The system MUST provide an administrator-configurable setting for that limit in the
  server configuration, and a documented default.
- **FR-003**: The default MUST be large enough that a simultaneous JOIN storm of the agreed size
  (2,000 simultaneous JOINs on one channel; the default is at least 3x the measured per-member
  volume of that storm and never above the ceiling) does not overflow any queue of a continuously
  reading client.
- **FR-004**: An invalid limit (zero, negative, non-integer, below one maximum-size protocol
  line, or above the 2 MiB ceiling) MUST be rejected at startup and on reload with an error
  identifying the setting; a failed reload MUST leave the previous configuration active.
- **FR-005**: The limit MUST apply uniformly to every connection of the server, whether plaintext
  or TLS. The limit is one server-wide setting; per-class or per-listener limits are out of scope.
- **FR-006**: When a connection's queue would exceed its limit, the system MUST close that
  connection and MUST NOT block the sender or delay any other connection.
- **FR-007**: Before closing for overflow, the system MUST attempt to send the client an `ERROR`
  line of the form `ERROR :Closing Link: <host> (SendQ exceeded)`, without blocking on the slow
  client and without enlarging the queue beyond its limit. Lines already queued but not yet
  written MAY be discarded so that the `ERROR` line goes out first. If the line cannot be
  written, the connection is closed anyway.
- **FR-008**: The QUIT reason that other channel members see for an overflow disconnect, and the
  disconnect reason in the connection monitoring log, MUST identify sendq overflow and be
  distinct from the reasons used for rate-limit flooding, keep-alive timeout and client QUIT.
- **FR-009**: Each overflow disconnect MUST produce exactly one connection-monitoring log entry
  that identifies the overflow, the connection token and the configured limit, and the system
  MUST count overflow disconnects so that a total is available to operators.
- **FR-010**: Handling an overflow that races another disconnect trigger for the same connection
  MUST produce one close, one log entry and one QUIT broadcast.
- **FR-011**: A connection that stops reading MUST still be closed once the data queued for it
  exceeds the limit, so queued protocol data per connection stays bounded by the limit plus one
  message. JVM object and message-metadata overhead is additional to this wire-data budget.
- **FR-012**: The documented policy MUST state: default size, unit, configuration key, minimum
  (16 KiB), ceiling (2 MiB), the exact client-visible `ERROR` text, the QUIT/log reason, and the worst-case
  queued wire-data budget (connections x limit), with additional JVM object/metadata overhead stated.
- **FR-013**: Reducing how many lines a JOIN burst generates (coalescing or limiting JOIN
  broadcasts, or an IRCv3 batching approach for JOIN fan-out) is out of scope for this feature
  (it changes what members receive and overlaps feature 012's fan-out ownership). This feature
  makes no change to which lines are sent to whom; fan-out reduction is tracked separately if
  measurements after this feature still show pressure.
- **FR-014**: Existing behavior that is not overflow MUST NOT change: message order and content,
  rate limiting, and the final-line guarantee that a line queued just before a close is still
  written.
- **FR-015**: The separate single-wave survival test, unchanged wave-of-100 latency test and
  integration tests MUST run through the normal test/load-test commands. The integration
  `loadTest` task may set a documented 2 GiB heap justified by the T003 probe; no latency bound
  in the existing wave-of-100 test changes.
- **FR-016**: `CapabilityExtension.contributeTags` MUST be a pure function of its message, with
  no wall-clock, random, recipient or mutable external-state dependency. Its contract and tests
  MUST enforce repeatable contributions (`msgid = message.messageId()`, `time = message.sentAt()`)
  so byte metadata can be shared across recipients while rendering remains at drain time.

### Key Entities

- **Outbound Queue Limit**: A per-connection ceiling, in bytes, on data waiting to be written to
  that connection. Has a default, a validated configurable value and a ceiling. Applied when a
  connection is opened.
- **Overflow Disconnect**: The closing of a connection because its queued data would exceed its
  limit. Carries a distinct reason, produces one `ERROR` attempt toward the client, one QUIT
  broadcast and one log entry, and increments a counter.
- **Overflow Counter**: A running count of overflow disconnects since the server started,
  available to operators alongside the log entries.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A simultaneous JOIN storm of the agreed size on one channel, from clients that read
  continuously, completes with 0 overflow disconnects and every client receiving its own
  end-of-NAMES, on a server with default settings, in a separate load-tagged survival test
  with no latency assertion (today: waves of 100 are required).
- **SC-002**: The existing `JoinStormLoadTest`, with 2,000 clients in waves of 100, keeps its
  bounds: storm under 15 s, quiet p99 under 500 ms and max under 1 s. No measured budget regresses
  by more than 10% against comparable baseline runs without written justification. The separate
  SC-001 single-wave survival test has no latency assertion.
- **SC-003**: A client that never reads is disconnected, with queued data never exceeding the
  limit by more than one message, within 5 seconds of the limit being reached.
- **SC-004**: 100% of overflow disconnects produce one log entry with a reason distinct from
  every other disconnect cause, and the overflow count matches the number of such entries.
- **SC-005**: 100% of overflow disconnects on a connection that can still accept a write deliver
  the `ERROR` line before the connection is closed.
- **SC-006**: With no explicit configuration the server uses the documented default; an operator
  can change the limit without modifying code; an invalid value prevents startup (or leaves the
  previous configuration active on reload) 100% of the time.
- **SC-007**: The worst-case queued wire-data budget at the default limit (connections x limit)
  is stated in the documentation, with additional JVM object/metadata overhead identified so
  operators can size deployment memory.
- **SC-008**: Byte-accounting tests compare each recipient's reserved bytes against its rendered
  wire line (including CRLF) and never undercount. Coverage includes capability combinations,
  client `+` tag forwarding, escaping, tag collisions, text truncation and a legal server-origin
  tag contribution with 4,094 escaped data bytes. Repeated/concurrent recipient accounting
  computes contribution byte metadata once per message/capability; purity tests cover existing
  message-tags and server-time implementations.

## Assumptions

- Closing the connection on overflow remains the policy; unbounded queues were rejected in the
  issue and are out of scope.
- "Healthy client" means one that reads its socket continuously at ordinary speed; the storm
  success criterion does not promise survival for a client that reads slowly enough to fall
  behind by more than the limit.
- T003 confirms the 512 KiB default from a 31,286-byte peak (3x = 93,858 bytes); floor and ceiling
  remain 16 KiB and 2 MiB. The revised accounting still requires rendered-wire validation.
- The listener accept-backlog limit found in #48 (comment) is a separate issue and not part of
  this feature.
- The change should not alter the wire format of any message sent other than the new `ERROR`
  line and the new QUIT reason text.
- Existing server-extension and capability code that writes to a connection continues to do so
  through the same path and gets the new limit without code changes of its own.
