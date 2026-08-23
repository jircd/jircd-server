# Contributing to JIRCD

Thanks for your interest in contributing. This project follows a
spec-driven workflow, so the process looks a little different from a
typical "fork, code, PR" project — please read this before opening an
issue or PR.

## Ground rules

- Be respectful and constructive. See our [Code of Conduct](CODE_OF_CONDUCT.md).
- Found a security issue? Do **not** open a public issue — see
  [SECURITY.md](SECURITY.md) instead.

## How this project works

Every feature is fully specified and planned before any code is written.
The artifacts for the current feature live under `specs/<feature>/`:

- `spec.md` — the *what* and *why*, written for anyone to review, with no
  implementation details
- `plan.md`, `research.md`, `data-model.md`, `contracts/` — the *how*,
  including the technical decisions and their rationale
- `tasks.md` (once generated) — the ordered, reviewable breakdown of
  implementation work

This means:

- **Spec changes come first.** If you're proposing new behavior or
  disagree with existing behavior, open an issue (or discuss on an
  existing one) referencing the relevant section of `spec.md` before
  writing code. Changes to requirements should land in the spec, not
  just in a PR description.
- **Implementation should trace back to the spec.** A PR that adds
  behavior not described in `spec.md`/`plan.md` will likely be asked to
  either update those documents first or narrow scope to what's already
  specified.
- The project's non-negotiable engineering principles (code quality,
  testing, UX consistency, performance) are recorded in the
  [constitution](.specify/memory/constitution.md). Reviews are expected to
  check compliance with it, not just correctness.

## Making a change

1. **Check the spec first.** Read `specs/001-ircv3-server/spec.md` (and
   `plan.md` if your change touches architecture) to understand current
   scope and decisions already made.
2. **Open an issue** describing what you want to change or add, if one
   doesn't already exist. For anything beyond a small fix, wait for
   discussion before investing in an implementation — it saves rework on
   both sides.
3. **Fork and branch** from `main`.
4. **Write tests first** for the behavior you're adding or fixing —
   see the constitution's Testing Standards principle. Tests must be
   deterministic; no flaky timing-based assertions.
5. **Keep changes focused.** One logical change per PR. Unrelated
   formatting or refactoring should be a separate PR.
6. **Open a PR against `main`**, describing what changed and why (not
   just what). Link the issue and, if relevant, the spec/plan section the
   change implements. If the PR fully resolves the issue, use a closing
   keyword (e.g. `Closes #4`) so it closes automatically on merge, and
   remove any now-stale planning labels (`deferred`, `roadmap`, etc.)
   from the issue as part of the same PR — don't leave sync-up as a
   separate manual pass after the fact.

## Code style

Formatting and static analysis (Spotless, SpotBugs, PMD — see
[`research.md`](specs/001-ircv3-server/research.md)) run as part of the
build and are expected to pass cleanly before review.

After cloning, run this once to enable the repo's pre-commit hook, which
runs `spotlessCheck` before each commit:

```sh
git config core.hooksPath .githooks
```

## Releasing

Maintainers cut a release by pushing a tag matching `vX.Y.Z` (e.g. `v0.2.0`)
to `main`. [`.github/workflows/release.yml`](.github/workflows/release.yml)
then builds a standalone distribution stamped with that version (overriding
the `0.1.0-SNAPSHOT` in `gradle.properties` for the build) and publishes it
as a GitHub Release with the `distZip`/`distTar` archives attached.

## Questions

Open a [discussion or issue](../../issues) if anything above is unclear.
