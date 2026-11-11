# CI workflow validation (US4)

Scope: `container.yml` (`jvm`, `publish-jvm`, `retag-jvm`, `native`, `publish-native`, `retag-native`), the `smoke-*` and `publish-*` jobs of `release.yml`, the `container-load` job of `load-test.yml`, the Dockerfile OCI labels and the Dependabot `docker` ecosystem. The JVM jobs shipped first; the native jobs and `container-load` came with the US3 PR.

## Checks run

- `actionlint` on every file in `.github/workflows/`: clean.
- `docker buildx build --target jvm --load` with the three `IMAGE_*` build args: builds; `docker inspect` shows `source`, `revision`, `version`, `licenses=Apache-2.0`, `title=jircd` and a `description` that names the JVM variant.
- `./gradlew :jircd-integration-tests:containerTest -Djircd.image=jircd:jvm-ci-test`: passed.

## Reviewed by hand against the workflow contract

- Workflow permissions are `contents: read`. Only `publish-jvm` and `retag-jvm` (container.yml) and `publish-jvm` (release.yml) add `packages: write`, at job level, and only on `refs/heads/main` (container.yml) or after both `release` and `smoke-jvm` pass (release.yml). The `release` job alone holds `contents: write`.
- Pull-request builds run only the read-only `jvm` job. Every checkout uses `persist-credentials: false`.
- `publish-jvm` has no `concurrency` group and pushes only `jvm-sha-<7>`. `retag-jvm` has a per-variant group with `cancel-in-progress: false`, checks the tip of `main` with `git ls-remote`, and moves `jvm` and `latest` with `imagetools create` from the SHA tag (no rebuild).
- Every third-party action is pinned by full commit SHA with a version comment. `gradle/actions/setup-gradle` keeps the SHA already used in `build.yml`.
- `${{ }}` values reach shell steps through `env:` or step outputs, never interpolated into the script.
- Test reports upload on failure.

## Native jobs and `container-load` (US3 PR)

- `actionlint` on every file in `.github/workflows/`: clean.
- `docker buildx build --target native --load` (arm64 locally): builds, labels set; `containerTest` against it passes (see [native.md](native.md)). The amd64 build runs only in CI.
- `native` mirrors `jvm`: read-only job, `persist-credentials: false`, reports uploaded on failure; it is not `continue-on-error`, so a native build or smoke-test failure fails the workflow (US4). `publish-native` needs `native`, runs on `main` only, pushes only `native-sha-<7>` for `linux/amd64` and has no concurrency group. `retag-native` has its own group (`container-retag-native`), the same tip-of-`main` check (a failed or empty `git ls-remote` fails the step) and moves only `native`; `latest` stays with `retag-jvm`.
- `release.yml`: `smoke-native` builds with `JIRCD_VERSION` and runs `containerTest`; `publish-native` `needs: [release, smoke-native]` and pushes `X.Y.Z-native` (amd64). `publish-jvm` still needs only `release` and `smoke-jvm`, so a native failure cannot block the JVM image, and the reverse.
- `load-test.yml` `container-load`: builds both images on amd64, runs `containerLoadTest` for the JVM image (gates the job), then builds and runs the native image with `continue-on-error`. The summary step runs `if: always()` through `.github/scripts/container-load-summary.py`, which prints a JVM-versus-native table with the percentage difference and flags native results more than 10% worse. A missing result file is reported in the table, not skipped. Actions in the new job are pinned by SHA (the older `load-test` job in the same file is unchanged).
- Every write permission carries a trailing comment; no `${{ }}` value is interpolated into a script.

## CI evidence from real runs (2026-11-11)

Checked with `gh run list` and `gh run view`; job results only, no logs.

`actionlint` was re-run on all seven files in `.github/workflows/` at `40e8f4f` (T046): clean.

| Run | Workflow and event | Result |
|---|---|---|
| 42395972677, 42397002962, 42398586380 | `container.yml`, PR #139 (JVM slice) | `jvm` failed once (42397002962), the next run passed; `publish-jvm` and `retag-jvm` skipped on the PR |
| 42402744968 | `container.yml`, `main` at `e56b2aa` (#139 merged) | `jvm`, `publish-jvm`, `retag-jvm` passed (first publish) |
| 42408360682 to 42427236882 | `container.yml`, PR #141 (US3), four runs | `jvm` and `native` passed; all `publish-*` and `retag-*` skipped on the PR |
| 42430873367 | `container.yml`, `main` at `5f2c700` (#141 merged) | all six jobs passed (`jvm`, `native`, `publish-jvm`, `retag-jvm`, `publish-native`, `retag-native`), including the first amd64 native build |
| 43073443036, 43074869234, 43076345640 | `container.yml`, `main` at `db48188`, `5f31810`, `40e8f4f` | all six jobs passed on each |
| 43075137633 | `container.yml`, Dependabot PR #145 | passed (publish and retag skipped) |
| 42626487901 | `load-test.yml`, scheduled, 2026-11-09 at `5f2c700` | `load-test` and `container-load` passed: JVM gate, native step and summary step all succeeded |

- **`release.yml`**: it has run once, for tag `v0.1.0-alpha` on 2026-08-19 (run 32289168462, failed at "Create GitHub release"). That was before `smoke-*` and `publish-*` existed (added in #139 and #141), so none of them has ever run on a real release. They are checked by `actionlint` and by hand only; the first real evidence will come with the next release tag.
- **`load-test.yml` `container-load`**: it has run once on a schedule (the run above, 2026-11-09) and passed. Earlier scheduled and manual runs predate the job. The JVM-versus-native comparison is in that run's job summary and is not copied here (no raw data in `validation/`).
- **The amd64 native build and smoke test** passed in CI on `main` (`native` job) from `5f2c700` on.

## Published tags

`docker manifest inspect ghcr.io/jircd/jircd-server:<tag>`, anonymous (empty Docker config), after `main`'s Container run for `40e8f4f` finished:

| Tag | Platforms | Note |
|---|---|---|
| `latest`, `jvm`, `jvm-sha-40e8f4f` | linux/amd64, linux/arm64 | same index digest (`sha256:2f2b75c5622e...`) |
| `native`, `native-sha-40e8f4f` | linux/amd64 | same index digest (`sha256:47034cf947c3...`) |
| `jvm-sha-5f31810`, `native-sha-5f31810` | as above | the previous `main` build still exists, so the sha tags do not move |

Release tags (`X.Y.Z`, `X.Y.Z-jvm`, `X.Y.Z-native`) do not exist yet: no release has run the new jobs.

## Not covered

The release workflow's `smoke-*` and `publish-*` jobs, until the first release after this feature.
