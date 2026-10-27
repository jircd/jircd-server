# Acceptance: Userhost in NAMES

**2026-10-27 — T025 complete.** Reconciles [spec.md](../spec.md) with the recorded evidence and coordinator-verified final gates. This closeout supersedes pending-review/final-acceptance notes in earlier validation records.

| Requirement | Result and evidence |
| --- | --- |
| FR-001: offers and CAP negotiation | Enabled discovery, pre/post-registration ACK/LIST and disabled offers pass scripted socket/lifecycle tests; the three external CAP targets pass. [Socket](socket.md), [irctest](irctest.md). |
| FR-002: requester-specific identities on all NAMES paths | Negotiated identities expand; ordinary/removed negotiation stays bare across targeted/bare NAMES and JOIN/SAJOIN, including explicit NAMES after SAJOIN. [Socket](socket.md), [review](review.md). |
| FR-003: permitted display host and cloak privacy | Own and negotiated-peer listings use the presented cloak; protected real hosts are excluded in the cloak cases. [Socket](socket.md), [review](review.md). |
| FR-004: visibility and unchanged replies | Private/secret visibility, completion and WHO behavior are preserved by the socket assertions. [Socket](socket.md). |
| FR-005: independent status/identity composition | Four negotiation combinations and exact composed prefixes/identities pass; the provider has no multi-prefix dependency. [Tests](tests.md), [socket](socket.md). |
| FR-006: 512-byte complete-token packing | Maximum UTF-8 identity and split-list tests assert complete unique membership, bounded lines including CRLF and final 366. [Byte budgets](byte-budgets.md), [socket](socket.md). |
| FR-007: conservative bounds and atomic rejection | Catalog assertions guard installed disabled identity/status reservations. The stricter WHO-driven loader/reload rejection preserves prior state; unsafe declarations use the generic fallback. [Byte budgets](byte-budgets.md), [tests](tests.md). |
| FR-008: configuration and lifecycle | Enabled/disabled/omitted state, controls/reload, eligible NEW/DEL, pruning and explicit renegotiation pass. [Tests](tests.md), [socket](socket.md). |
| FR-009: all-or-nothing CAP requests | Unavailable combined requests NAK without partial negotiation. [Tests](tests.md), [socket](socket.md). |
| SC-001: identity/privacy matrix | All recorded acceptance scenarios and negotiated/cloak/four-way matrix assertions pass in the final clean-test build. [Build](build.md), [socket](socket.md). |
| SC-002: complete bounded lists | Boundary/multi-line assertions find no lost/duplicate/truncated identities or oversized 353 sections, and verify 366 completion. [Byte budgets](byte-budgets.md), [socket](socket.md). |
| SC-003: external CAP conformance | Candidate: 199 passed, 153 skipped, 0 failed; exactly three fail→pass changes among the same 352 server cases. Both capabilities are enabled; only multi-prefix can be enum-declared, while userhost-in-names is requested by wire name. Main requires omission of that single unknown config key; this documented comparison exception leaves no unexplained case changes. [irctest](irctest.md). |
| SC-004: performance budgets and review justification | All asserted absolute budgets pass. Alternating NAMES rerun request-rate losses of 17.1% / 46.7% / 24.3% were coordinator-checked. The report explicitly justifies opted-in larger-output cost and attributes old-harness fan-out variation using unchanged code paths, without claiming measured causality or precise ≤10% bounds. [Performance](performance.md), [review](review.md). |

## Final gates

The coordinator verified the following after the latest performance edits:

```sh
./gradlew cleanTest build sonar --no-parallel -Dsonar.qualitygate.wait=true -Dsonar.scm.disabled=true -Dsonar.branch.name=015-userhost-in-names
```

**BUILD SUCCESSFUL**: tests, Spotless, SpotBugs, PMD and coverage verification passed; SonarCloud quality gate passed with **0 branch issues**. Independent Claude review found no correctness defects; all review requests are addressed. [Build](build.md) and [review](review.md) record these results.

**No unresolved blockers.** T024 and T025 are complete. Only summarized evidence and reproduction commands are kept in the project; original data stays outside it. This documentation closeout executed no Gradle or git commands. Commit/PR still awaits user authorization.
