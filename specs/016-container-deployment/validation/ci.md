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

## Not yet covered

CI evidence from the PR run (including the amd64 native build and the first `container-load` numbers).
