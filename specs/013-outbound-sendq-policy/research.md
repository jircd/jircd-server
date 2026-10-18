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

- **Decision**: bytes. Each queued entry carries an estimated wire size; `queuedBytes` (atomic) is
  added at enqueue and subtracted when the writer thread takes the entry. Overflow when
  `queuedBytes + size > limit`.
- **Estimate**: sum of UTF-8 lengths of prefix, command, params and tag key/values plus separators
  and CRLF, computed without serializing. Fan-out entries add a fixed allowance (128 B) for the
  tags rendered at drain time (`time`, `msgid`). The estimate is an upper bound, so real memory use
  stays within the limit.
- **Rationale**: serializing 2,000 times per JOIN on the broadcaster thread would turn a cheap
  enqueue into the cost the registry is trying to avoid; an upper-bound estimate keeps the memory
  guarantee.
- **Alternatives**: serialize at enqueue (exact, more CPU, moves per-recipient tag rendering to the
  sender); count entries but raise 256 (does not express memory, keeps the hidden unit).
- **Queue type**: `LinkedBlockingQueue` (no entry cap; entries are at least ~10 B so the byte
  budget bounds the count).

## D3: Default, floor and ceiling

- **Decision**: default 512 KiB (524288), floor 16 KiB (16384), ceiling 2 MiB (2097152), the user's decision.
- **Rationale**: a JOIN line is about 50-80 B; 2,000 JOINs is about 100-160 KB per member before
  other traffic, so 512 KiB is about 3x headroom. Floor is above one maximum line (8191 tag bytes +
  512 command bytes + CRLF) so a legal message always fits an empty queue. Ceiling keeps a typo from
  disabling the protection and matches the 1-2 MB per class of classic IRC servers. The default
  (512 KiB, or whatever T003 measures) MUST NOT exceed the ceiling.
- **Open**: the default MUST be re-validated by a measurement task (queued bytes per member during
  the 2,000 storm, via a test hook) before the value is fixed; the plan's number is a starting
  point, not a measured result.
- **Worst case memory** at default: 10,000 stuck connections x 512 KiB = 5 GiB; at the ceiling,
  10,000 x 2 MiB = 20 GiB. Both documented; the first is an argument against a higher default.

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

- **Decision**: cherry-pick `a847df5` (`JoinStormLoadTest`) onto this branch, then change it to one
  simultaneous wave. Coordinate with the #48 PR so the file does not conflict.
- **Open**: #48's PR is open as #59 (branch `feature/issue-48-join-storm-load-test`); if it merges first, rebase and drop the cherry-pick.
