# Implementation Plan: Project Site

**Branch**: `017-project-site` | **Date**: 2026-11-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/017-project-site/spec.md`

## Summary

Replace the 2010 GitHub Pages page at `jircd.org` with a documentation site built with Astro Starlight:

- the guides are Markdown in `docs/` (readable on GitHub), loaded by the site project in `site/`;
- the home page and the four sections (User, Administrator, Developer, API reference) share one navigation, search and theme;
- the home page is `docs/index.mdx`, inside the same loaded collection; until the guides land it links only to pages that exist;
- the API reference is Javadoc for the extension API only (`net.jircd.core.extension` and `net.jircd.core.extension.capability`, plus only the packages and types their public signatures use; internal protocol machinery is excluded), built by one new core-Gradle `Javadoc` task that is part of `./gradlew build`, and served at `/api/`;
- a new workflow builds the site on pull requests (link check fails the PR) and deploys only from `main` (push or manual run) with the official Pages actions;
- the README shrinks to a quick guide of about 100 lines.

Technical decisions, their evidence and fallbacks are in [research.md](research.md); the page-to-source map and the workflow contract are in [contracts/site-contract.md](contracts/site-contract.md); how to validate is in [quickstart.md](quickstart.md). No server behaviour changes. Tracked by [#159](https://github.com/jircd/jircd-server/issues/159).

## Technical Context

**Language/Version**: Markdown and Astro (TypeScript config) for the site; Java 25 for the Javadoc task (Gradle toolchain, unchanged)

**Primary Dependencies**:

- New, in `site/` only: `astro`, `@astrojs/starlight`, `@astrojs/mdx` (the home page), `starlight-links-validator` (versions pinned by `package-lock.json`, current at writing: 7.4.13, 0.43.1, 0.27.0)
- New in CI only: Node (pinned by `site/.nvmrc`, minimum 22.23.4), `actions/setup-node`, `actions/upload-pages-artifact`, `actions/deploy-pages`
- No new Gradle plugin or dependency; `gradle/verification-metadata.xml` and the keyring stay untouched (research R4)

**Storage**: N/A (static files)

**Testing**: the site build itself (Astro build with link validation) and the Javadoc task (part of `./gradlew build`) act as the tests; a claims check per guide page (FR-009); a manual cut-over verification. No production Java code changes, so no unit tests are added; `./gradlew build` must stay green.

**Target Platform**: GitHub Pages (static hosting) at `jircd.org`; contributors preview locally with Node 22+

**Project Type**: documentation site beside a multi-module Gradle server

**Performance Goals**: the home page is static and loads without third-party requests; the whole site build finishes within the 15-minute publication budget of SC-005 (expected a few minutes); the load tests and server budgets are unaffected

**Constraints**: CI and Sonar must not regress; each PR stays under about 120,000 diff characters; every third-party action pinned by SHA; Node only in the docs job and for local preview (the Javadoc task, which needs only the JDK, is the one docs step in `./gradlew build`)

**Scale/Scope**: about 15 to 20 guide pages migrated from the 600-line README, one home page, one generated API reference

## Constitution Check

*GATE: pass before research; re-checked after design.*

| Principle | Status | Notes |
| --- | --- | --- |
| I. Code Quality (responsibility, dead code, public contracts) | PASS | One generator, one Javadoc task, no speculative layers. Guides are the single copy of their content; the README and `CONTRIBUTING.md` link instead of duplicating. The Javadoc package list is a documented public contract (research R4). || II. Testing Standards (tests first, behaviour proven) | PASS with note | No production code changes. The tests of this feature are the failing-first docs checks: the link validator and the Javadoc task (wired into `./gradlew build`) are added in PR2 before the pages that depend on them, and the claims check proves guide statements against code and tests. A deliberately broken link is used once to prove that the PR check fails (quickstart scenario 3). |
| III. User Experience Consistency | PASS | The site gives one navigation, theme and wording for the three audiences; guide terms follow the server's own messages and configuration keys. Accessibility and narrow-screen requirements are SC-008. |
| IV. Performance Requirements | PASS (not affected) | No server path changes. The site makes no third-party requests; the build has a publication-time budget (SC-005). |
| Quality Gates | PASS | `./gradlew build` per PR; docs job per PR; independent cross-vendor review of each slice; the manual cut-over is an explicit task with maintainer confirmation. |
| Development Workflow | PASS | Work is tracked by GitHub issue #159 (task T001, done), PR descriptions explain why. |

No violations; the Complexity Tracking table is not needed.

## Project Structure

### Documentation (this feature)

```text
specs/017-project-site/
├── spec.md
├── plan.md
├── research.md
├── quickstart.md
├── tasks.md
├── checklists/requirements.md
└── contracts/site-contract.md
```

`data-model.md` is omitted: the entities in the spec are files and pages whose map is the site contract.

### Source Code (repository root)

```text
docs/                           # guides, Markdown with frontmatter; the home page is index.mdx (PR2)
├── index.mdx                   # home page: Starlight splash, hero in frontmatter, MDX components
├── user/                       # connecting, TLS, commands, capabilities, channel modes
├── admin/                      # install, configuration reference, operations topics
└── developer/                  # build and test, modules, capability provider (SPI)
site/                           # Astro Starlight project (PR2)
├── package.json, package-lock.json, .nvmrc
├── astro.config.mjs            # site URL, sidebar, plugins, markdown.processedDirs
├── src/content.config.ts       # docs collection: glob loader on ../docs (md, mdx) + docsSchema
├── src/plugins/…               # relative .md link rewrite (remark)
├── scripts/                    # check-lockfile.mjs (registry-only lockfile), check-api-links.mjs (links into /api/)
├── src/content/docs/           # fallback location of the whole content tree if R2's proof fails (accepted)
├── src/assets/                 # logo
└── public/CNAME                # jircd.org
build.gradle.kts                # + extensionApiJavadoc task, wired into build (PR2); sonar.exclusions for site/** only if the Sonar scan reports site/** files (T019)
.coderabbit.yaml                # + !site/package-lock.json path filter (PR1)
.github/workflows/pages.yml     # build on PR, deploy only on main push or dispatch on main (PR2)
.github/dependabot.yml          # + npm entry for /site (PR2)
README.md                       # rewritten to a quick guide (PR3)
.gitignore                      # + site/node_modules, site/dist
```

**Structure Decision**: guides outside `site/` for GitHub readability; the Astro project reads them through a content loader with a recorded fallback (research R2). No Java source layout changes.

## Delivery Plan (stacked PRs)

| PR | Content | Tasks | Gate |
| --- | --- | --- | --- |
| 1 | Spec documents (`specs/017-project-site/`), bot path filters for the lockfile; `part of #159` | T001–T004 | Docs and bot config only; `./gradlew build` green; claims check of the spec |
| 2 | Site skeleton, Home page (`docs/index.mdx`, linking only to pages that exist), `pages.yml` (lockfile guard, `/api/` link check, deploy-only concurrency and tip-of-`main` check), `site/scripts/`, Dependabot entry, Javadoc task wired into `build`, `.gitignore`, Sonar exclusion of `site/**` only if the scan reports `site/**` files (T019); `part of #159` | T010–T023 (incl. T013a, T013b) | `./gradlew build`; docs job green on the PR; local Sonar run; cross-vendor review |
| cut-over | Right after PR 2 merges: Pages source switch, `github-pages` environment restricted to `main`, domain and HTTPS check; `gh-pages` tagged `legacy-gh-pages` and retired after a quiet period; manual run from a non-`main` branch checked once the workflow is on `main` | T050–T053 (incl. T050a, T052a) | Each step maintainer-confirmed |
| 3 | Guides in `docs/`, guide links on the Home page and sidebar sections, README rewrite; `fixes #159` | T030–T046 (incl. T045a) | Claims check per page; docs job green; `./gradlew build`; cross-vendor review |
| after | Harvest of review findings | T054 | After the last merge |

Each slice is sized for about 120,000 diff characters (Sourcery's limit is 150,000). The lockfile is the main PR2 risk: it stays in PR2 with the CodeRabbit path filter landed in PR1 and the Sourcery dashboard Path Filter set by the maintainer before PR2 opens; the untested assumption and the fallback split are in research R10. PR3 is the largest by prose; if the migrated guides exceed the budget, the Administrator Guide goes in its own PR (3b) and the User and Developer Guides stay in 3a. Each split PR adds the guide links and sidebar entries of its own pages (T045a); the README rewrite goes in the last split PR, and until then the README sections stay, so no content is dropped before its guide page has merged.

## Risks

| Risk | Mitigation |
| --- | --- |
| Starlight cannot load or fully process guides outside `site/` (research R2) | Proof task T012 before any guide is written; fallback moves the content directory only |
| `package-lock.json` blows the diff budget (R10) | CodeRabbit path filter in PR1, Sourcery dashboard Path Filter set by the maintainer (T004); measure in T013; if Sourcery still counts it, split `package.json` and the lockfile into a PR before the skeleton |
| Pages workflow fails on `main` between PR2 and the Pages source switch | Cut-over right after PR2 merges (T050), not after PR3 |
| Manual dispatch from a non-main branch deploys | `deploy` requires `github.ref == 'refs/heads/main'`; checked after the merge in T052a, because GitHub offers `workflow_dispatch` only once the workflow is on the default branch (T020 checks the `pull_request` behaviour) |
| Lockfile excluded from the review bots hides a redirected dependency (R10) | Lockfile guard `check-lockfile.mjs` before `npm ci` (registry URLs only, integrity hashes enforced by `npm ci`); lockfile changes stay in the PR's file list (T013a) |
| A queued older deploy overwrites newer content, or PR builds block publication | `concurrency` on the `deploy` job only; the deploy job skips when its commit is no longer the tip of `main` (R5, T017) |
| Links into `/api/` break when Javadoc changes | Offline check over the assembled `site/dist` (R3, T013b) |
| Rollback blocked by the `main`-only environment rule, or legacy page lost | Rollback removes the rule first; `legacy-gh-pages` tag pushed before the branch is deleted (R8, T053) |
| Guides drift from the code | Claims check per page (T033, T040, T044); a short "last verified against" note is not added, because it rots; the check runs per PR |
| Cut-over breaks `jircd.org` | Ordered steps, rollback (environment rule, then source) while the branch exists, and from the `legacy-gh-pages` tag afterwards (R8) |
| README content lost during the move | A section-by-section move table in T045; the check that each former section is reachable from a guide or the Home page |
| Javadoc has unresolved references to non-published packages (R4) | List them in T016; add only the needed packages, in one place (a build property) |

## Complexity Tracking

No constitution violations to justify.
