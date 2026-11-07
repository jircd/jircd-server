# CI workflow validation (JVM-only slice, US4)

Scope: `container.yml` (`jvm`, `publish-jvm`, `retag-jvm`), the `smoke-jvm` and `publish-jvm` jobs of `release.yml`, the Dockerfile OCI labels and the Dependabot `docker` ecosystem. The native jobs come with the US3 PR.

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

## Not yet covered

CI evidence from the PR run, and the native jobs.
