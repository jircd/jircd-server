# Research: Project Site

Versions and facts below were checked on 2026-11-18 (npm registry, GitHub releases, Starlight's published docs, this repository). Items marked **Prove in PR2** are decisions that need a working build to confirm; each has a fallback.

## R1. Generator: Astro Starlight

- **Decision**: Astro Starlight (`@astrojs/starlight` 0.43.1 on `astro` 7.4.13 at the time of writing; the versions are pinned in `site/package.json` and refreshed by Dependabot).
- **Rationale**: Markdown-first, built-in search (Pagefind), light/dark theme, responsive layout, sidebar from files or config, and a small dependency surface for a docs-only site. It needs Node `>=22.23.4`.
- **Alternatives** (maintainer decision, final):
  - Zensical: version 0.1.0, too new; its predecessor Material for MkDocs reaches end of life in November 2026.
  - Docusaurus: heavier (React app, versioning we do not need).

## R2. Layout: Markdown in `docs/`, Astro project in `site/`

- **Question**: can Starlight load guides from a top-level `docs/` directory instead of `site/src/content/docs/`?
- **Findings**:
  - `docsLoader()` is fixed to `src/content/docs/`; its only option is `generateId()`. It has no `base` option.
  - Starlight requires the `docs` collection to use `docsSchema()`, but not `docsLoader()`; Astro's built-in `glob({ pattern, base })` loader accepts a `base` outside `src/` (relative to the project root, so `../docs`).
  - Starlight's extra Markdown processing (heading anchor links, aside directives, code-block RTL) is applied by default only to content loaded by `docsLoader()`; other directories are listed in the `markdown.processedDirs` option, which takes paths relative to the project root.
  - Relative links between `.md` files (`[x](../admin/config.md)`) are not rewritten into site URLs by Starlight, and GitHub needs them to be relative `.md` links to stay navigable.
  - GitHub renders `docs/*.md` frontmatter as a table at the top of the file; acceptable.
- **Home page**: `docs/index.mdx` (Starlight splash template, hero in frontmatter, feature cards as MDX components via `@astrojs/mdx`), so the whole content tree is in the loaded collection. Starlight's own default location (`src/content/docs/index.mdx`) would be outside the `../docs` collection and the home page would not be found. GitHub shows MDX as source, which is acceptable for one page.
- **Decision**: `docs/` holds the guides and the home page; `site/src/content.config.ts` defines `docs` with `glob({ pattern: '**/*.{md,mdx}', base: '../docs' })` and `docsSchema()`, and lists `../docs` in `markdown.processedDirs`. A small remark plugin in `site/` rewrites relative links ending in `.md` into site URLs (and fails the build on a target that does not exist). **Prove in PR2** (task T012): asides, anchors, sidebar order and the link rewrite work for a guide outside `site/`, and the link validator sees the rewritten links.
- **Fallback** (accepted by the maintainer in advance; no further confirmation needed), if T012 shows loading from `docs/` does not work cleanly: keep the guides and the home page in `site/src/content/docs/` and make `docs/` a pointer README, or symlink `docs` to it. Moving files is a one-commit change before the guides are written (PR3), so the cost of discovering this late is small.
- **Alternatives considered**: copying `docs/` into `site/src/content/docs/` in a prebuild step (two copies; local edits confuse authors; rejected); generating the sidebar from frontmatter `sidebar.order` (chosen, no separate config to keep in sync).

## R3. Link and structure checking

- **Decision**: `starlight-links-validator` (0.27.0 at the time of writing) as a Starlight plugin: it fails the build on a broken internal link or anchor. It validates the built site, so the rewritten links of R2 are covered. Javadoc is added after the Astro build, so links into `/api/` must be exempted; the plugin's exclusion option (name and pattern syntax to be confirmed against its documentation, **prove in PR2**, T013) is used for that, and the job separately checks that `site/dist/api/index.html` exists.
- **Rationale**: one dependency, runs in the same build the PR job already runs, no extra action to pin.
- **Alternative**: a separate link-checker action (`lychee`) over the output directory; heavier to pin and it duplicates anchors checks. Kept as a fallback if the plugin cannot see `/api/` or the outside-`src` content.
- **External links** (repository, GitHub, IRCv3 specs) are not checked on PRs, since flaky remote hosts would fail unrelated PRs; they are listed in `quickstart.md` as a manual spot check.

## R4. API reference: which packages

- **Question**: which types make up "the extension API only", and how to build Javadoc when there is no Javadoc generation task today (`build.gradle.kts` only sets UTF-8 encoding for Javadoc tasks, lines 58-60), no `module-info` and one `package-info.java`?
- **Findings**:
  - The SPI is `net.jircd.core.extension` (`Extension`, `ServerExtension`, `CapabilityExtension`, `AbstractCapabilityExtension`, `ServerContext`, `ExtensionRegistry`, ...) and `net.jircd.core.extension.capability` (`CapabilityRegistrar`, `RuntimeSnapshot`, the hook and result types; it has the module's only `package-info.java`).
  - Bundled capability and server-extension modules also import `net.jircd.protocol` types (`Message`, `Command`, `CapabilityName`, `NumericReply`, `Hostmask`, `ChannelName`), `net.jircd.core.session` (`ClientSession`, `Channel`, `MessageDelivery`, `Recipients`, ...), `net.jircd.core.session.command` (`CommandHandler`, `Replies`) and `net.jircd.core.config` (`ServerConfiguration`, `ConfigurationReloader`).
- **Decision (maintainer, final)**: extension API only: `net.jircd.core.extension`, `net.jircd.core.extension.capability` and `net.jircd.protocol`, plus only what is needed for links from those to resolve. The session, session.command and config packages listed above are candidates for that "needed for links" set, not part of the scope: T016 runs Javadoc over the three packages, lists the unresolved references, and adds just the packages (or none) that those references require. The list lives in one place (a `build.gradle.kts` property) and is recorded in `contracts/site-contract.md`.
- **Build mechanism**: one root Gradle task `extensionApiJavadoc` of type `Javadoc` (core Gradle, no new plugin, so no change to `gradle/verification-metadata.xml`). It takes the source directories of `jircd-protocol` and `jircd-core`, restricts them to the package list with `include()`, uses `jircd-core`'s compile classpath, and writes to `build/docs/extension-api`. The workflow copies that directory to `/api/` after the Astro build.
- **Doclint**: start with `-Xdoclint:all,-missing`, so malformed Javadoc fails the build but missing comments do not (the existing code is documented in the SPI packages only). Tightening is out of scope.
- **Is `build` affected?** Yes, by decision (maintainer): `check` depends on `extensionApiJavadoc`, so `./gradlew build` and `build.yml` fail on a broken Javadoc comment in the published packages, locally and in CI. It needs only the JDK, adds no plugin and does not change the 85/75 coverage floor; T016 records the added build time. The pages workflow still runs the task itself to produce the `/api/` output.
- **Alternatives**: aggregate Javadoc over all modules (publishes internals, rejected by the maintainer); a Javadoc Gradle plugin (new plugin, verification metadata churn, no gain); introducing `module-info.java` to export the API (large change to the server, out of scope).

## R5. Publishing workflow

- **Decision**: new `.github/workflows/pages.yml` with two jobs.
  - `build` (runs on `pull_request` and `push`/`workflow_dispatch` to `main`; path filter `docs/**`, `site/**`, `jircd-core/src/main/java/**`, `jircd-protocol/src/main/java/**`, `build.gradle.kts`, `.github/workflows/pages.yml`; the README is not on the site, so it is not a trigger): checks out, sets up JDK 25 and Gradle (same pinned `gradle/actions/setup-gradle` SHA as `build.yml`), runs `extensionApiJavadoc`, sets up Node (pinned, `site/.nvmrc`), `npm ci`, `npm run build` (links validated), copies the Javadoc into `site/dist/api`, and on `main` uploads with `actions/upload-pages-artifact`.
  - `deploy` (`needs: build`; job condition `if: github.ref == 'refs/heads/main' && github.event_name != 'pull_request'`, so only a push to `main` or a manual run on `main` deploys and a manual run from any other branch does not; environment `github-pages`; permissions `pages: write`, `id-token: write`): `actions/deploy-pages`. The environment's own branch rule is a second guard the maintainer may add.
- `concurrency: { group: pages, cancel-in-progress: false }`, so a deployment is never cut off halfway.
- **Action pinning**: current releases at the time of writing: `actions/checkout` v7.0.1, `actions/setup-node` v7.1.0, `actions/setup-java` v6.3.0, `actions/upload-pages-artifact` v5.0.0, `actions/deploy-pages` v5.0.1, `actions/configure-pages` v6.0.0. The existing workflows pin `actions/*` by tag and third-party actions (Gradle) by SHA; the handoff requires every third-party action by full SHA, and the new workflow pins all of them by SHA for simplicity, with the tag in a comment. Dependabot's `github-actions` entry already keeps them current. Resolve each SHA with `gh api repos/<owner>/<repo>/git/ref/tags/<tag>` at implementation time and verify it against the release page.
- **`configure-pages`**: not needed for a site with an explicit `site` and no `base` (custom domain at the root); omitted.
- **Pull requests from forks**: the build job needs no secrets, so it runs; the deploy job never runs on pull requests.
- **Pages source switch**: until the maintainer switches the repository's Pages source from "Deploy from a branch" to "GitHub Actions", `deploy-pages` fails with an error saying Pages is not configured for Actions. The PR2 workflow therefore ships with `deploy` enabled; the cut-over follows right after PR2 merges (R8), so the window with a failing deploy on `main` is short, and the first-run failure before the switch is expected and documented in `quickstart.md`; alternative considered: a repository variable gate (`vars.PAGES_ENABLED`), rejected as one more thing to forget. The maintainer also restricts the `github-pages` environment to `main` (deployment branch rule) at the cut-over (R8, T050a); the `deploy` job condition already limits it to `main` pushes, the environment rule enforces it in the platform.
- **Custom domain**: with the Actions source the domain is a repository setting (`jircd.org` is already set); the `CNAME` file on `gh-pages` is not used any more. `site/public/CNAME` containing `jircd.org` is still added so the artifact documents the domain and a later settings reset is visible in review.

## R6. Dependabot

- **Decision**: add a `npm` entry for directory `/site`, weekly, grouped `site-npm` for minor and patch updates, `open-pull-requests-limit: 2`, matching the other entries.

## R7. Node pinning

- **Decision**: `site/.nvmrc` holds the Node major version used in CI (22 LTS line, minimum `22.23.4` required by Starlight; the exact patch is chosen at implementation). `actions/setup-node` reads `node-version-file: site/.nvmrc`. `package.json` `engines.node` repeats the minimum. Node is needed only by the docs job and local preview; no Gradle task calls Node.

## R8. Cut-over and rollback

- **Timing**: the cut-over happens right after PR2 merges, not after PR3. A Home page plus the API reference already beats the 2010 page, and waiting for PR3 would leave a pages workflow failing on `main` for as long as PR3 takes. Later guide PRs deploy as normal `main` pushes.
- **Decision**: ordered, each step confirmed by the maintainer: (1) merge PR2, first successful `build` job and a deploy attempt; (2) switch Pages source to GitHub Actions in repository settings, keeping the custom domain and "Enforce HTTPS", and restrict the `github-pages` deployment environment to the `main` branch (deployment branch rule) before the first deploy; (3) rerun the workflow to deploy; (4) verify `https://jircd.org` and `https://www.jircd.org` (content, certificate); (5) after a quiet period of the maintainer's choosing, delete the `gh-pages` branch.
- **Rollback**: before (5), switching the Pages source back to the `gh-pages` branch restores the legacy page within minutes. After (5), the old page can be restored from git history (the branch's last commit hash is recorded in the cut-over task before deletion).
- The Pages HTTPS certificate is tied to the custom domain setting, not to the source, and is expected to survive the switch; step (4) verifies it.

## R9. Content sources and accuracy

- **Source material**: the 600-line `README.md` (sections: What is JIRCD, Project status, Getting started, Logging, Reloading and stopping, Running in a container, The native variant, OPER protection, Connection limits, Registration timeout, MOTD and admin contact, Listener accept backlog, Generating an administrator credential, Running a standalone build, Writing a capability provider, Outbound queue policy, Contributing, Security, License), plus `deploy/` and the `specs/` contracts.
- **Mapping**: see `contracts/site-contract.md`. Where the README holds information that belongs to two audiences (for example TLS and the container), the guide for the audience that acts on it owns it and the other links to it.
- **Claims check** (FR-009): per guide page, a list of claims with the code or test that proves each (config key → `ServerConfiguration` loader and its test; command → handler class; capability → its module and the `CAP LS` integration test; channel mode → the mode parser). The check is delegated to a worker and recorded in the PR description, not committed as raw data (project rule: no raw validation data in the repository).
- **Gaps discovered while migrating** (a command documented but missing, or the reverse) become separate issues; guides state what the code does.

## R10. Diff size and lockfile

- **Risk**: `site/package-lock.json` for Astro plus Starlight is large (hundreds of kilobytes). Sourcery counts diff characters; the 150,000-character limit could be exceeded by the lockfile alone.
- **Decision (maintainer)**: keep `site/package-lock.json` in PR2 and exclude it from the review bots, with the exclusions landing in PR1 (task T004) so they are on `main` before PR2 opens:
  - CodeRabbit: add `!site/package-lock.json` to `path_filters` in `.coderabbit.yaml` (the existing mechanism for `gradle/verification-metadata.xml`).
  - Sourcery: the maintainer decided that the Sourcery dashboard Path Filter is the mechanism, and that there is no `.sourcery.yaml`. Research on 2026-11-18: the current Sourcery documentation ([Review settings](https://docs.sourcery.ai/reviews/configure/), and the full docs at https://docs.sourcery.ai/llms-full.txt) describes review configuration as dashboard settings, "Path Filters: a single glob list on the General tab that excludes matching files from the reviewed diff" (Repo settings), and does not document a `.sourcery.yaml` for reviews. The maintainer sets the Path Filter (`site/package-lock.json`) before PR 2 opens (T004 (b)).
  - **Untested**: whether an excluded path also stays out of the 150,000-character per-PR limit and the 250,000 per-developer weekly budget. The docs say path filters exclude files "from the reviewed diff" and say nothing about the usage meter. Test: open PR2 and read Sourcery's status.
  - **Fallback**: if Sourcery still counts the lockfile, split `package.json` and `package-lock.json` into their own PR before the skeleton (the build is not run for it; the stack becomes four PRs).
  - Smallest dependency set regardless: Astro, Starlight, `@astrojs/mdx`, the links validator; no `sharp` (no images beyond the SVG logo).

## R11. Home page, logo and brand

- **Decision**: the home page is `docs/index.mdx` (R2) and uses Starlight's splash template (hero with tagline and two actions: Quick start, GitHub), a short feature list (modular core, runtime-toggleable extensions, IRCv3 capabilities, container-ready), a status paragraph taken from the README's "Project status", and a quick start snippet. Logo: an SVG wordmark created for the site (no design work beyond text and one brand colour); the brand colour is set through Starlight's CSS custom properties with accessible contrast in both schemes (checked with a contrast tool at implementation, SC-008).
- No analytics, cookies or third-party fonts (system font stack), so there is nothing to disclose.

## R12. Sonar, Spotless and other quality tools

- Sonar analyses only the Gradle projects; `site/` and `docs/` are not analysed. If the Sonar scan picks up `site/**/*.js` or `.ts` files (it scans JS/TS by default), `sonar.exclusions` gains `site/**` in the root build (PR2); verified with the local Sonar run described in `AGENTS.md`. Workflow files fall under the existing `.github/**` coverage exclusion; S7637 (pinned actions) is why every action is pinned.
- Spotless/PMD/SpotBugs are Java-only and unaffected. The pre-commit hook runs `spotlessCheck` only.
