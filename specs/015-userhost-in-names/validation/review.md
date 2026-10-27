# Review completion (T024)

Reviewed the diff against `.github/REVIEW_CHECKLIST.md`. The implementer’s self-review below is complemented by the coordinator-verified final SonarCloud scan and independent Claude review. T024 is complete.

- Ownership/privacy: production behavior is confined to the new module's identity hook. No production core changes, direct hostname lookup, status formatting, mutable state or new locks/I/O. Only MemberSnapshot's permitted displayHost is used. No hard dependency on multi-prefix; the descriptor test checks this explicitly.
- Bounds/hostile input: extra width uses checked long arithmetic and ReplyLimits; UTF-8 max identities and composed status/identity catalog are tested. Actual-provider negative tests also verify malformed/over-bound facts and unsafe declarations produce INVALID_RESULT with nickname fallback. Existing serializer, whole-token packing and generic fault fallback remain responsible. Independent WHO versus NAMES bounds are accurately documented; unsafe reload remains atomic.
- CAP/lifecycle: before/after registration, removal, all-or-nothing rejection, explicit/implicit notification classes, withdrawal/pruning and explicit renegotiation are covered through real sockets. Already-captured output is not treated as forbidden after DEL.
- Content assertions: full tokens and final closing text are asserted, with exact expected sets and no duplicate/lost members, four negotiation combinations, cloaked/ordinary display hosts, targeted/bare/JOIN/SAJOIN and private/secret channel visibility. Provider-absence catalog tests now include the new module and prove multi-prefix still works without identity decoration.
- Cleanup/determinism: functional fixtures use try-with-resources and bounded protocol/PING barriers. The load fixture owns sockets in a Clients AutoCloseable, closes all even on one IOException, and preserves primary test failures. Targeted PMD CloseResource suppressions document borrowed/list-owned sockets, matching existing load fixture patterns. Timing samples and names use Locale.ROOT. No sleeps, retries or timeouts were increased to hide failures.
- Review fixes: corrected the inherited 353 rejection-size expectation from 515 to 607 (installed extra identity bytes); corrected load nicknames to fit the nine-byte default; baseline mode uses an environment variable that reaches forked test JVMs; updated initial design's incorrect prediction of tighter effective configuration acceptance. WHO is already stricter by S+5 bytes.
- Docs and public API: README/config contract/provider Javadoc updated. New constant/module only; no existing public signatures changed. Java/Gradle ignore rules already cover generated outputs, with no need for unrelated ignore-file additions.

No unresolved self-review finding. Coordinator-run irctest and performance results are summarized in [irctest.md](irctest.md) and [performance.md](performance.md); final gate results follow below.

## Review round 1 edits

Addressed the reviewer’s seven requests: package count, WHO-driven test naming/comments, negotiated peer cloak privacy and post-SAJOIN explicit NAMES coverage, qualified bundled-provider budget wording/Javadoc, fixture/import/output cleanup, self-contained task scope/scripted T023 note, and removal of raw build logs. All review requests are addressed and the edits are covered by the coordinator’s final clean-test build below.

## Final coordinator-verified results — 2026-10-27

After the latest performance documentation edits, the coordinator verified:

```sh
./gradlew cleanTest build sonar --no-parallel -Dsonar.qualitygate.wait=true -Dsonar.scm.disabled=true -Dsonar.branch.name=015-userhost-in-names
```

**BUILD SUCCESSFUL**: Spotless, SpotBugs, PMD, tests and aggregate coverage verification passed. SonarCloud branch quality gate passed with **0 open branch issues**. The earlier `java:S6126` finding in UserhostInNamesLoadTest is fixed.

Independent other-vendor review by **Claude** found no correctness defects. All its review requests are addressed, including the peer cloak/post-SAJOIN checks and documentation/fixture cleanup listed above. The coordinator also spot-checked the replacement NAMES throughput deltas against source data: **17.1% / 46.7% / 24.3%** match.

No unresolved review findings or blockers. [acceptance.md](acceptance.md) reconciles the requirements and final gates. This record uses the supplied verified results; no Gradle or git commands were executed for closeout. Summaries only; original validation data remains outside the repository. Commit/PR awaits user authorization.

PR #120 Sourcery follow-up (2026-10-27): rejected all ISO controls in NAMES hook output (including C1/U+0085), used bare nicknames for missing ident/display host, strengthened hidden-owner checks independent of decoration, and documented both capability constants; six regressions failed before the fixes, then the requested scoped tests passed (58 cases) and `./gradlew spotlessApply` passed. USER/Hostmask validation is unchanged; full build/Sonar for this batch remain coordinator-owned.
