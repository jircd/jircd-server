# Quickstart: Validating Feature 012

Run from the worktree root. Design details are in [data-model.md](data-model.md) and
[contracts/state-operations.md](contracts/state-operations.md).

## Prerequisites

- JDK 25 via the Gradle toolchain (`./gradlew --version`)
- `git config core.hooksPath .githooks` (pre-commit runs `spotlessCheck`)

## 0. Baseline (once, on `main` / before step 1)

```bash
./gradlew build                 # must be green
./gradlew loadTest              # all asserted bounds pass; record Gradle durations for information only (SC-005)
```

## 1. Per-step scoped checks

| Step | Command | Expected |
|---|---|---|
| Helpers (US3) | `./gradlew :jircd-core:test --tests '*Recipients*' --tests '*RepliesListed*' --tests '*OutboundMessageBudget*'` | green; NAMES lines ≤ 512 bytes; one NICK/AWAY/QUIT per recipient |
| Transitions (US1) | `./gradlew :jircd-core:test --tests '*ChannelRegistry*' --tests '*DisconnectCleanup*' --tests '*MembershipStress*'` | green; zero membership mismatches, zero empty channels, serial KICK/PART outcome |
| Mutators (US4) | `./gradlew :jircd-core:test --tests '*ChannelTest*' --tests '*Mode*'` and `./gradlew :jircd-server-extensions:admin:test` | green; grant to non-member refused; +s/+p exclusive |
| Regression net (US2) | `./gradlew :jircd-integration-tests:test` | green with **no existing** integration test modified (test-support additions allowed) (`git diff --stat origin/main -- jircd-integration-tests` shows only the new characterization tests and test-support changes) |

## 2. Encapsulation checks (SC-003, SC-004)

```bash
# No direct mutation through accessors outside the owners (expect no output):
rg -n --glob '**/src/main/**' '\.(members|operators|voiced|activeModes|bans|invited|channelMemberships|userModes)\(\)\.(add|remove|clear|removeIf|addAll|retainAll)' .

# Fan-out loops replaced (expect only Recipients.java):
rg -ln --glob '**/src/main/**' 'writer\(\)\.enqueueRaw' jircd-core/src/main/java/net/jircd/core/session jircd-server-extensions
```

The compiler also enforces this: `addMember`, `removeMember`, `addMembership` and
`removeMembership` are package-private, and the views throw `UnsupportedOperationException` on
mutation.

Some single-recipient sends (INVITE to target, Replies.send) legitimately keep their own
`enqueueRaw`. The second search is for reviewing channel-wide loops, not a zero-hit gate.

## 3. Comment cleanup check (SC-006)

```bash
git diff --name-only origin/main -- '*.java' | xargs rg -n '\b0\d\d-[a-z-]+ FR-\d+|\bFR-\d+'   # expect no output
```

## 4. Full gate and manual walkthrough (Quality Gates 1–5)

```bash
./gradlew build
./gradlew loadTest      # all load tests pass their asserted bounds; Gradle durations are information only
```

Manual walkthrough (Constitution III). Start the server locally, connect two clients (for
example with `nc` or an IRC client), and run JOIN, NAMES, MODE +o/+v, KICK, PART, NICK, AWAY
(with `CAP REQ :away-notify` on the other client) and QUIT in shared channels. Confirm the lines
match those from the baseline build.
