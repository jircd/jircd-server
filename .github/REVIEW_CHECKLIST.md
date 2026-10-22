# Pre-PR review checklist

SonarCloud and Sourcery review every PR (SonarCloud only when the PR's workflow has `SONAR_TOKEN`, so not on PRs from forks). This list collects what they have flagged on this repo's merged PRs (#28–#94), so a change can be checked against it before the PR opens. Numbers in brackets are example PRs.

## SonarCloud rules this repo keeps hitting

Most frequent first. The gate fails on new issues, and non-gating ones get fixed before merge too.

Main code:

- **S7467**: an unused catch parameter is `_` (`catch (IOException _)`). With S7466, an unnamed local is `var _`.
- **S1186**: an empty method body gets a comment saying why it is empty.
- **S3077**: no `volatile` field of a non-thread-safe type (a lambda, a collection, a mutable object). Use `AtomicReference` or a final thread-safe type.
- **S6878 / S6916 / S6880**: use record deconstruction patterns rather than accessor calls, a `when` guard rather than a lone `if` in a pattern case, and `switch` rather than an `if`/`else` chain on one variable.
- **S1612**: a lambda that only calls a method becomes a method reference.
- **S3776**: keep cognitive complexity at 15 or less per method; extract helpers.
- **S1130**: no `throws` clause for an exception the body cannot throw.
- **S2445**: synchronize only on `private final` fields.
- **S3358**: no nested ternaries.
- **S2093**: close resources with try-with-resources.
- **S1192**: a string literal used three or more times becomes a constant.
- **S1181**: do not catch `Throwable` or `Error`. Catch `RuntimeException` or the specific exception at an extension boundary.
- **S2629**: no string building or method calls in log arguments; use `{}` placeholders and guard costly ones with `isXxxEnabled()`.
- **S6353**: regex classes are concise (`\d`, not `[0-9]`).
- **S8688**: pass a `ZoneId` or `Clock` to `.now()`.
- **S127**: do not change a `for` loop's counter or bound inside the body.
- Also seen: S1858 (`toString()` on a String), S1481 (unused local), S1117 (a local shadowing a field), S1125 (redundant boolean literal), S3824 (`computeIfAbsent` instead of `get` plus a null check), S6126 (text blocks for multi-line strings), S107 (more than seven parameters).

Tests:

- **S5778 / S5783**: an `assertThrows` / `assertThatThrownBy` lambda contains exactly one call that can throw; build the arguments before it.
- **S2925**: no `Thread.sleep` in tests; wait on a condition (a latch, polling with a deadline, or Awaitility if it's already a dependency).
- **S5853**: chain consecutive AssertJ assertions on the same subject.
- **S5841**: assert `isNotEmpty()` before `allMatch` / `allSatisfy` / `doesNotContain`.
- **S3415**: the computed value goes in `assertThat(...)`, the expected literal in the matcher.
- **S2699**: every test asserts something.
- **S5838**: use AssertJ's dedicated assertion (`hasSize`, `isEmpty`, `contains`) rather than `isEqualTo` on a derived value.

## What Sourcery keeps finding

Check the diff for each of these before opening the PR.

- **Failure and cleanup paths.** When a constructor, bind, accept, close or extension call fails midway, everything already opened gets closed and every registration gets undone: listeners, sockets, sessions, nickname claims, channel members. A retry or second close must not stop a running server or truncate queued output. [#36, #39, #59, #63, #80, #81, #84]
- **Concurrency and reload.** A reader sees either the old or the new configuration, never a mix of the two. Already-accepted connections keep the limits they were accepted under unless the contract says otherwise. Fan-out reads capability and membership state that is consistent for each recipient. Check races around the last member leaving, a reload while a command runs, and echoes built from a stale snapshot. [#52, #62, #63, #79, #88]
- **Protocol size limits.** Every reply fits the 512-byte command section, or is fitted or skipped with a specific WARN; never a silent drop by the writer. Bound reads of operator-supplied files (MOTD), output bursts against SendQ, and log volume per repeated request. [#52, #62, #87, #92, #94]
- **Hostile or oversized input.** Bound CPU and memory: no backtracking blow-up, no allocation that grows with the input before the cap applies, no per-member work repeated on each JOIN. Treat equivalent masks as the same entry. [#52, #88, #91, #92]
- **Extension boundaries.** Decide what happens when extension code throws or returns bad data, and keep core state consistent when it does (no empty channel left behind, no lost JOIN outcome, no crash on an unexpected mode kind). [#52, #61, #88, #92]
- **Validation edge cases.** Treat empty, null, negative, zero, duplicate and out-of-range values explicitly: an empty parameter, an explicit `null` YAML section, a missing bind address (which binds every interface only where the contract says so, as for `ServerConfiguration.Listener`), a negative limit, a duplicate CAP offer, an empty CAP REQ. Validate every configuration source, including reload and constructors. [#37, #39, #61, #67, #79, #80, #87]
- **Rejected requests change nothing.** A rejected MODE, CAP REQ, PASS or OPER leaves state exactly as it was, and the client gets the documented error. [#61, #79, #82, #87]
- **Counters and accounting.** Counts stay right across nickname changes, unregistered connections, failed setup and peaks (LUSERS), and byte counts stay right across dequeues and overflow (SendQ). [#63, #84]
- **Security.** Secrets never reach logs, error messages or reports, including nested YAML values and extension settings. Lockout trackers stay safe when full, when verification is slow and when operators share a name. [#37, #70, #82]
- **Determinism.** Output does not depend on the JVM's locale or time zone (number formatting, date formatting). [#87]
- **Persistence of changes.** A reloaded value survives restart where the contract says it should, and cached file content notices edits. [#67, #87]
- **Public API compatibility.** Changing a public constructor or record breaks callers. Keep the old signature, or follow the constitution's breaking-change rule: give the migration path in the PR body. While the project is unreleased (0.1.0-SNAPSHOT) the version bump comes with the first release. [#39, #52, #63, #82, #83, #84, #87]
- **Tests that can pass while the code is wrong.** Assert content (exact params, fitted text, log lines, final state), not just reply codes or the absence of an exception. Make concurrency tests prove that the overlap happened. Don't reuse a consumed stream. Drive the real handler, not a shortcut. Check that a test fails without the fix. [#52, #59, #65, #81, #82, #87, #92, #94]
- **Test reliability.** Tests have bounded waits and always clean up (no hang on a failed latch, no leaked connection). They don't depend on platform-specific exception messages, and ordinary tests don't run with load tests. [#39, #41, #59, #90]
- **Docs and contracts.** Update `specs/` contracts, README and Javadoc in the same PR, and keep them consistent with each other and with the code. Claims match the implementation: an O(n·m) algorithm is not "linear time". [#62, #67, #79, #80, #82, #83, #84, #91]
