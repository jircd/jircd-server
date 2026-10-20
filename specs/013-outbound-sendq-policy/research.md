# Research: Outbound Queue Overflow Policy

## Current behavior (verified in code)

- `SessionWriter`: `ArrayBlockingQueue<Runnable>(256)`; `enqueue`/`enqueueRaw` use `offer`; a full
  queue calls `handleOverflow()` -> `onOverflow` -> `DisconnectCleanup.cleanup(session, "Excess Flood")`
  on the **sender's thread** (set in `ConnectionHandler`).
- `close()` offers a poison pill and waits up to 2 s for the drain; with a full queue the offer
  fails and it interrupts immediately (so today's overflow close never waits). A second `close()`
  only waits.
- Write failure (`IOException`) also calls `onOverflow`, so it is also logged "Excess Flood".
- Entries are closures: fan-out entries are rendered per recipient at drain time (tags depend on the
  recipient's capabilities), so their wire size is unknown at enqueue.
- Config: `ServerConfiguration` record; loader helper `positiveIntWithinCeiling`; per-connection
  values reach `ConnectionHandler` through suppliers bound to `reloader.current()`, so a reload
  affects connections opened afterwards.
- `ConnectionMonitorLog` only has `connected` and `disconnected(reason)` static methods.

## D1: Scope of the four options in the issue

- **Decision**: do options 1, 2 and 4. Leave option 3 (reduce fan-out) out. *(Accepted by the user.)*
- **Rationale**: 1 fixes the reported failure; 2 and 4 make the remaining overflows diagnosable. 3 changes what
  members receive, interacts with feature 012's ownership of fan-out, and is only worth doing if
  measurements after 1 still show pressure.
- **Alternatives**: do 3 now (coalesced/batched JOIN, `draft/multi-prefix`-style compaction): larger
  protocol surface and test matrix, not needed to meet the acceptance criteria.

## D2: Unit and accounting of the limit

- **Decision**: a conservative upper bound on outbound wire bytes. Each entry carries its charge;
  atomically reserve at enqueue and release when the writer takes it. Reject an enqueue whose
  reservation exceeds `limitBytes`. The queue is a `LinkedBlockingQueue` bounded by this budget.
- **Recipient charge**: prefix/command/parameters with UTF-8 and trailing-parameter syntax,
  forwarded client `+` tags only if the recipient negotiated message-tags, and the sum of
  contributions from enabled capabilities that recipient negotiated, plus tag delimiters and
  CRLF. Count escaped tag values, including expansion of semicolon, space, backslash, CR and LF.
  There is no fixed allowance. Count actual selected contributions, however large their legal
  tag data is; preserve the existing per-origin and combined protocol budgets.
- **Shared computation**: `CapabilityExtension.contributeTags(message)` is pure and depends only
  on the immutable message: msgid uses `message.messageId()` and time uses `message.sentAt()`.
  Compute escaped contribution byte metadata once per enabled capability/message, memoized
  safely across concurrent recipients. Cache by capability identity and message, with lifetime
  bounded by that message; recipients sum only their selected contributions. Document and test
  the purity contract; both existing implementations already satisfy it.
- **Bound, not exact wire size**: summing selected tag entries before existing merge precedence
  may count overwritten keys; untruncated component bytes may exceed the eventual text length.
  Both overcount safely. Ordinary non-colliding, untruncated messages have exact wire charges.
  Delimiters include `@`, `=`, `;`, separating spaces, trailing-parameter `:` and CRLF as applicable.
  Accounting and drain-time capability selection must stay consistent: no queued entry may
  acquire uncharged decorations through capability negotiation or reload. Rendering remains at
  drain time; tests cover queued entries across those changes as well as normal selection.
- **Validation**: compare reserved bytes with actual rendered wire bytes for each capability
  combination, escaped/multibyte values, client-tag filtering, merge collisions and text
  truncation. Include a legal 4,094-byte server tag data contribution (4,096 bytes with framing).
  Test memoization once per message/capability under concurrent fan-out and across new messages.
- **Rationale**: per-message memoized metadata avoids serialization or contribution-byte
  recomputation per recipient without assuming a maximum smaller than the protocol permits.
- **Rejected**: the former fixed 128-byte allowance undercounts legal large server tags;
  serializing per recipient at enqueue moves rendering onto the broadcaster; a message-count
  cap does not express the wire-data budget.

## D3: Default, floor and ceiling

- **Decision**: retain default 512 KiB (524288), floor 16 KiB (16384), ceiling 2 MiB (2097152).
- **T003 complete**: the scratch single-wave 2,000-client probe records peak queued bytes per
  member of **31,286 B**; **3x = 93,858 B**, below the default and ceiling. The probe counts the
  former fan-out allowance; retain that historical methodology in `baseline.md` rather than
  relabeling it as a measurement of the revised estimator. No default increase is needed.
- **Protocol floor**: one maximum-size outbound line (8191 tag-section bytes plus 512
  command-section bytes including CRLF) fits the floor. The upper-bound estimator must also
  admit a maximum-size legal line into an empty queue at this floor.
- **Heap evidence**: both scratch variants exhaust the default 512 MiB test heap. A 2 GiB
  test heap reaches end-of-NAMES for all 2,000 clients and records the peak. Permit the build's
  integration `loadTest` task to set `maxHeapSize = "2g"` with a comment citing that probe.
  This is a test-harness resource setting, not a larger sendq or a deployment heap requirement.
- **Separate targets**: keep `JoinStormLoadTest` in waves of 100 with its existing 15 s storm,
  500 ms quiet p99 and 1 s max bounds. Add `SingleWaveJoinStormLoadTest` for survival of 2,000
  simultaneous JOINs, all end-of-NAMES and zero overflow/unexpected disconnects, with no latency
  assertion. Retain finite failure/cleanup timeouts and the hardened shared failure/ClientPool
  mechanisms. The probe's quiet p99/max of 1,789.5 ms is recorded, not turned into a relaxed
  latency bound. Compare before/after latency runs with the same heap configuration.
- **Worst-case queued wire data**: 10,000 x 512 KiB = 5 GiB; at the ceiling, 10,000 x 2 MiB =
  20 GiB. JVM object/cache overhead is additional; the byte budget is not an exact heap measure.

## D4: Server-wide only, not per connection class

- **Decision**: one `sendqLimitBytes` for all listeners. *(Accepted by the user.)*
- **Rationale**: the server has no connection classes (searched code and specs); inventing them for
  one setting is scope creep. A per-listener or per-class override can be added later without
  changing the key's meaning.
- **Alternatives**: per-listener override (cheap, but nothing asks for it); per-class (needs a class
  model that does not exist).

## D5: Overflow state machine and the ERROR line

- **Decision**: on the first overflow (compare-and-set flag) the writer (1) rejects all further
  enqueues, (2) discards the queued backlog, (3) enqueues the `ERROR` line and the poison pill in
  an allowance that is outside the limit, (4) runs the disconnect on a **new virtual thread**, not on the sender's.
- **Rationale**: the sender must never wait; `close()` can wait up to 2 s for a drain, which was
  harmless only because the full queue made the pill fail. Discarding the backlog (accepted by the user) is acceptable
  because the connection is being dropped, and it is what lets the `ERROR` line go out first on a
  slow-but-reading socket. A truly stuck socket blocks in `write`; `close()` still ends it after
  the existing 2 s bound by interrupting.
- **Alternatives**: keep the backlog and append `ERROR` (delivers a client far behind, mostly stale
  lines, before the error); write the `ERROR` directly from the sender (cross-thread socket write,
  forbidden by the writer's design).
- **Edge**: a client that is reading but very slowly could still receive the `ERROR`; one that reads
  nothing never does, and that is the documented best-effort.

## D6: Reason strings, log and counter

- **Decision**: reason `SendQ exceeded`, used for the QUIT broadcast and for the `disconnected`
  entry; plus a new `sendq-overflow` entry (connection, limit, queued bytes, cumulative total) and
  a static `AtomicLong` total in `ConnectionMonitorLog`. The write-failure path keeps its current
  reason, so the two causes are no longer conflated by sharing the callback.
- **Rationale**: matches the existing log style (`connection-event=...`); no metrics subsystem
  exists, so the counter is exposed through the log line (a monotonic `overflowTotal`).
- **Alternatives**: JMX/metrics endpoint (new subsystem, out of scope).

## D7: Reload semantics

- **Decision**: new limit applies to connections opened after the reload; existing writers keep the
  limit they started with. Documented in the contract.
- **Rationale**: same as `rateLimit`; live-resizing a counter under traffic adds races for no
  need.

## D8: Test dependency on #48

- **Resolved**: PR #59 merges as `f9dfac1`; the coordinator rebases this branch onto main.
  T001 is satisfied without a cherry-pick. Keep the hardened `JoinStormLoadTest` and
  `JoinStormSupportTest`; the separate single-wave test reuses their failure/ownership patterns.
