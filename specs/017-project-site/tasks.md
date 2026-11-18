# Tasks: Project Site

**Input**: Design documents in `specs/017-project-site/` (spec.md, plan.md, research.md, contracts/site-contract.md, quickstart.md)

**Format**: `- [ ] Txxx [P] [USn] Description with file path`. `[P]` means it can run in parallel with its neighbours (different files, no dependency on an open task). IDs are grouped by pull request (T00x, T01x–T02x, T03x–T04x, T05x) so that other documents can cite them; they are not consecutive.

**Stories** (spec.md): US1 visitor/home page (P1), US2 user guide (P2), US3 administrator guide (P2), US4 developer guide and API reference (P3), US5 publishing (P2), US6 README (P3).

**Tests**: the tests of this feature are its checks: the Astro build with link validation, the Javadoc task, the claims check per guide (FR-009) and the manual quickstart scenarios. Each is added before the content that depends on it.

**Delivery**: stacked GitHub PRs, bottom-up squash merges, each passing `./gradlew build` and staying under about 120,000 diff characters (`git diff origin/main...HEAD | wc -c`). Workers never commit; the coordinator commits after the maintainer reviews the diff. Spec documents go in their own commit.

## PR 1: Spec documents

- [X] T001 Issue: [#159](https://github.com/jircd/jircd-server/issues/159) "[Feature] Project site at jircd.org with User, Administrator and Developer guides" (filed by the maintainer, so no search or filing remains). PR 1 and PR 2 say `part of #159`; the last PR of the stack (PR 3) says `fixes #159`
- [ ] T002 Run speckit-analyze over `specs/017-project-site/` and fix real inconsistencies between spec.md, plan.md, research.md, contracts and this file
- [ ] T004 Keep the PR 2 lockfile out of the review bots (research R10): (a) DONE: `!site/package-lock.json` added to `path_filters` in `.coderabbit.yaml`; (b) Maintainer: set the Sourcery dashboard Path Filter (Repo settings, General, Path Filters) to exclude `site/package-lock.json`. This is the only Sourcery mechanism (no `.sourcery.yaml` is added; maintainer decision). Step (b) must be done before PR 2 opens; the task stays open until the maintainer confirms it
- [ ] T003 Claims check of the PR: every task id, URL, package name, version and file path cited in the spec documents exists or is explicitly marked "proposed"; check the diff against `.github/REVIEW_CHECKLIST.md`; independent cross-vendor review of the diff; measure the diff size; open PR 1 (`part of #159`)

**Checkpoint**: spec documents and the bot path filters (T004) reviewed and merged; no site code, workflow or README changed.

## PR 2: Site skeleton, Home page, publishing, API reference

### Setup

- [ ] T010 Create the Astro project in `site/`: `package.json` (engines node `>=22.23.4`, scripts `dev`, `build`, `preview`), `.nvmrc`, `tsconfig.json`, and add `site/node_modules/`, `site/dist/` and `site/.astro/` to `.gitignore`
- [ ] T011 Write `site/astro.config.mjs` (`site: https://jircd.org`, Starlight with title, GitHub social link, sidebar sections User, Administrator, Developer and the `/api/` link, `markdown.processedDirs: ['../docs']`) and `site/src/content.config.ts` (docs collection: `glob({ pattern: '**/*.{md,mdx}', base: '../docs' })` with `docsSchema()`; the home page is `docs/index.mdx`, inside the loaded collection, and `@astrojs/mdx` is added for it)

### Foundational: proofs before content

- [ ] T012 Prove the layout of research R2 with one throwaway page `docs/user/connecting.md`: asides, heading anchors, `sidebar.order`, and a relative `.md` link rewritten by a remark plugin in `site/src/plugins/rewrite-md-links.mjs` (build fails on a missing target). Also render `docs/index.mdx` as a splash page with a hero and an MDX component. If it cannot work cleanly, apply the fallback (content in `site/src/content/docs/`, decided as accepted by the maintainer, no further confirmation needed), record the result in research.md R2 and adjust the paths in plan.md and the site contract
- [ ] T013 Install `starlight-links-validator` in `site/`, confirm its exclusion option for `/api/`, prove it fails the build on a broken link (quickstart scenario 3); generate `package-lock.json` (kept in PR 2) and measure its size and the PR's diff size. If the bots' path filters of T004 do not keep the lockfile out of Sourcery's 150,000-character limit or weekly budget (untested; research R10), apply the fallback: `package.json` and the lockfile go in their own PR before the skeleton

### US1: Home page (P1)

- [ ] T014 [US1] Write `docs/index.mdx` (Starlight splash template: hero in frontmatter; the feature cards as MDX components; description, what is distinctive, status, quick start for the container and the standalone build, links to the repository, the guides and `/api/`), `site/src/assets/logo.svg`, the brand colour in `site/src/styles/custom.css`, and `site/public/CNAME` containing `jircd.org`; check contrast in both schemes and the 320 px layout (quickstart scenario 4)
- [ ] T015 [US1] Claims check of the home page: the status text, the capability and extension claims and the quick-start commands against the README, `deploy/`, the Dockerfile and the code; record the claims list in the PR description

### US4: API reference (P3)

- [ ] T016 [P] [US4] Add the `extensionApiJavadoc` task to `build.gradle.kts` (core `Javadoc` type over `jircd-protocol` and `jircd-core` sources restricted by one package-list property, `-Xdoclint:all,-missing`, output `build/docs/extension-api`) and wire it into `./gradlew build` (`check` depends on it), so broken Javadoc fails CI and local builds. Scope is decided: `net.jircd.core.extension`, `net.jircd.core.extension.capability` and `net.jircd.protocol`, plus only the packages needed for links to resolve; run it, list the unresolved references, add only those packages to the property and to `contracts/site-contract.md`; confirm `gradle/verification-metadata.xml` needs no change and that the build time and coverage floor are unaffected

### US5: Publishing (P2)

- [ ] T017 [P] [US5] Write `.github/workflows/pages.yml` per the workflow contract: `build` job (JDK 25, Gradle setup, `extensionApiJavadoc`, Node from `site/.nvmrc`, `npm ci`, `npm run build`, copy Javadoc to `site/dist/api`, check `site/dist/api/index.html`, upload with `actions/upload-pages-artifact` on `main`), `deploy` job (`actions/deploy-pages`, environment `github-pages`, job-level `pages: write` and `id-token: write`, and `if: github.ref == 'refs/heads/main' && github.event_name != 'pull_request'`, so a `workflow_dispatch` from another branch never deploys), `concurrency` group `pages`; resolve every action to its full commit SHA with the tag in a comment and verify each against the release page
- [ ] T018 [P] [US5] Add the `npm` entry for `/site` (weekly, group `site-npm` for minor and patch, limit 2) to `.github/dependabot.yml`
- [ ] T019 [US5] Run the local Sonar analysis from `AGENTS.md` on the branch; if it reports `site/**` files, add `site/**` to `sonar.exclusions` in `build.gradle.kts`; fix every open issue it lists
- [ ] T020 [US5] Run quickstart scenarios 1 to 4 locally and attach the results (not raw logs) to the PR description; run `./gradlew build`; verify with an open PR that the workflow's `build` job passes and `deploy` is skipped, and that a `workflow_dispatch` run from the PR branch builds but skips `deploy` (quickstart scenario 7); open PR 2 with `part of #159`

### PR 2 wrap-up

- [ ] T021 Check the diff against `.github/REVIEW_CHECKLIST.md` (failure and cleanup paths, pinned actions, docs claims, hostile input in the link-rewrite plugin)
- [ ] T022 Measure the diff (`git diff origin/main...HEAD | wc -c`), independent cross-vendor review, open PR 2 stacked on PR 1
- [ ] T023 After the PR 2 merge, wait for the first `main` run: `build` passes and `deploy` either succeeds or fails only with the "Pages is not configured for Actions" error (expected until T050, which follows immediately)

**Checkpoint**: the site builds and the workflow works on PRs; the home page and `/api/` exist; guides are placeholders only (T012's throwaway page is removed or kept as the first real guide page). The cut-over (T050 onwards) starts now.

## PR 3: Guides and README

### US2: User Guide (P2)

- [ ] T030 [P] [US2] Write `docs/user/connecting.md` and `docs/user/tls.md` (what a client needs, first connection, TLS settings); source: sample configuration, `deploy/`, README §Getting started
- [ ] T031 [P] [US2] Write `docs/user/commands.md` listing every command the server implements, from the command handlers, with one line each and its numeric replies where relevant
- [ ] T032 [P] [US2] Write `docs/user/capabilities.md` (the IRCv3 capabilities and their enablement) and `docs/user/channel-modes.md` (every channel mode the code accepts), from the capability modules and the mode parser
- [ ] T033 [US2] Claims check of the User Guide: each command, capability and mode traced to its handler, module or test, in both directions (documented but missing, implemented but undocumented); attach the claims list to the PR description (no raw data committed)

### US3: Administrator Guide (P2)

- [ ] T034 [P] [US3] Write `docs/admin/install-container.md` (JVM and native image variants, config mount, uid, read-only root, HUP and TERM) from README §Running in a container and §The native variant
- [ ] T035 [P] [US3] Write `docs/admin/install-standalone.md` from README §Running a standalone build
- [ ] T036 [US3] Write `docs/admin/configuration.md`: every key the configuration loader reads with type, default, reloadable or not, and effect, derived from `ServerConfiguration` and its loader; no key without a test or code reference
- [ ] T037 [P] [US3] Write `docs/admin/logging.md`, `docs/admin/reload-stop.md`, `docs/admin/oper-protection.md` and `docs/admin/connection-limits.md` (including the registration timeout) from the matching README sections
- [ ] T038 [P] [US3] Write `docs/admin/motd-admin-contact.md`, `docs/admin/listener-backlog.md`, `docs/admin/admin-credentials.md` and `docs/admin/sendq.md` from the matching README sections
- [ ] T039 [US3] Set the Administrator sidebar order and add cross-links among the admin pages and from the User Guide
- [ ] T040 [US3] Claims check of the Administrator Guide: every key, default, command-line step and behaviour traced to code or a test; follow the guide from an empty machine through the container and the standalone route (quickstart scenario 5); attach the claims list to the PR description

### US4: Developer Guide (P3)

- [ ] T041 [P] [US4] Write `docs/developer/build-test.md` (Gradle commands, `./gradlew build`, formatting, hooks, load tests tagged `load`, dependency verification pointer) and `docs/developer/modules.md` (module graph from `AGENTS.md` and `settings.gradle.kts`)
- [ ] T042 [P] [US4] Write `docs/developer/capability-provider.md` from README §Writing a capability provider (SPI): registration table, fault handling, runtime enable and disable, migration table; link every type to `/api/`; leave out the internal status paragraph and validation paths
- [ ] T043 [P] [US4] Write `docs/developer/contributing.md` linking `CONTRIBUTING.md`, `SECURITY.md` and the spec workflow, without copying their rules
- [ ] T044 [US4] Claims check of the Developer Guide: every command, module name, type name and registration verified against the repository; confirm every type named in the capability guide has a page under `/api/`

### US6: README (P3)

- [ ] T045 [US6] Rewrite `README.md` to the contract (about 100 lines, badges kept, quick start for the container and the standalone build, how to connect, links to the site and each guide); write a section-by-section table of where each of the 19 former sections went and verify nothing accurate was lost; update links elsewhere that point at README anchors (`CONTRIBUTING.md`, `AGENTS.md`, `.github/`, specs) so they still resolve
- [ ] T046 [US6] Run `./gradlew build` and the docs build, quickstart scenarios 5 and 6, check the diff against `.github/REVIEW_CHECKLIST.md`, measure the diff (split into 3a and 3b if over about 120,000 characters, research and plan), independent cross-vendor review, open PR 3 stacked on PR 2 (the last PR of the stack: `fixes #159`)

**Checkpoint**: every former README topic has one home in a guide; all links resolve; `./gradlew build` and the docs job pass.

## Cut-over (maintainer-confirmed, right after PR 2 merges)

A Home page plus the API reference already beats the 2010 page, and switching early avoids a pages workflow failing on `main` while PR 3 is written. PR 3's guides then deploy as ordinary `main` pushes. Each step needs the maintainer's explicit confirmation before it runs; none is automated by an agent.

- [ ] T050 Maintainer: switch the repository's Pages source from the `gh-pages` branch to "GitHub Actions", keeping the custom domain `jircd.org` and "Enforce HTTPS"
- [ ] T050a Maintainer: restrict the `github-pages` deployment environment to the `main` branch (Settings, Environments, github-pages, Deployment branches and tags: selected branches, `main` only), before the first deploy
- [ ] T051 Re-run the Pages workflow (`workflow_dispatch`) and confirm `deploy` succeeds
- [ ] T052 Verify `https://jircd.org` and `https://www.jircd.org`: HTTP 200, valid certificate, the new home page and `/api/` (quickstart scenario 8; guide pages appear after PR 3 merges); report the outcome
- [ ] T053 Maintainer: after a quiet period of their choosing, record the last commit hash of `gh-pages` in the cut-over notes, then delete the `gh-pages` branch
- [ ] T054 After the PR stack merges, run the harvest of AGENTS.md: collect the review bots' findings, add recurring categories to `.github/REVIEW_CHECKLIST.md` in the next small PR

## Requirement coverage

FR-001 T050–T052; FR-002 T014–T015; FR-003 T030–T033; FR-004 T034–T040; FR-005 T041–T044; FR-006 T016, T017; FR-007 T011, T012, T014, T030–T043; FR-008 T045; FR-009 T015, T033, T040, T044; FR-010 T013, T016, T017, T020; FR-011 T017, T020, T023; FR-012 T017, T018; FR-013 T050–T053; FR-014 T014, T020 (scenario 4); FR-015 T016, T019, T020, T022, T046; FR-016 T004, T022, T046 and the delivery notes.

## Dependencies and order

- PR 1 → PR 2 → PR 3 (stacked); T004 lands in PR 1, before PR 2 opens; T050–T053 (incl. T050a, which comes before T051) follow the merge of PR 2 (not PR 3); T053 follows T052; T054 follows the last merge.
- Within PR 2: T010 → T011 → T012 → T013 → T014; T016, T017 and T018 are independent of the content tasks once T010 exists (T017 needs T016's task name); T019 and T020 come last.
- Within PR 3: each guide's claims check (T033, T040, T044) follows its writing tasks; T045 follows all of them (it needs the final guide URLs).
- Parallel work (disjoint files): T030, T031, T032; T034, T035, T037, T038; T041, T042, T043. One writer per working tree: parallel writers use separate worktrees or are given disjoint paths.

## MVP

PR 2 alone is the MVP: it replaces the 2010 page with a modern home page and an API reference as soon as the maintainer switches the Pages source right after the PR 2 merge (T050). The guides (PR 3) and the README (T045) follow without blocking it.
