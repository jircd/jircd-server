# Quickstart: Validating the Project Site

Run from the repository root of a worktree. Prerequisites: JDK 25 (Gradle toolchain), Node 22.23.4 or newer (see `site/.nvmrc`), a browser. Contracts: [site-contract.md](contracts/site-contract.md).

## 1. Build the API reference (PR2 onwards)

`./gradlew build` runs this task too (a Javadoc error fails it).

```bash
./gradlew extensionApiJavadoc
test -f build/docs/extension-api/index.html
```

Expected: the task succeeds; the index lists only the packages named in the contract; a type named in the capability-provider guide (for example `CapabilityRegistrar`) has a page.

## 2. Build and preview the site

```bash
cd site && node scripts/check-lockfile.mjs && npm ci && npm run build
cp -R ../build/docs/extension-api dist/api
node scripts/check-api-links.mjs
npm run preview
```

Expected: the lockfile guard, the build with link validation and the `/api/` link check pass; the preview serves the home page and `/api/` (PR 2) and, from PR 3, the three guide sections with sidebar and search.

## 3. Prove the link check fails

Add `[x](./does-not-exist.md)` to a guide, run `npm run build`.

Expected: the build fails and names the file and the link. Revert the edit. Then point a guide or the home page at a missing Javadoc page (`/api/does/not/Exist.html`) and an existing page with a missing anchor, build, copy the Javadoc and run `node scripts/check-api-links.mjs`: it fails and names the link. Revert.

## 4. Responsive, theme and accessibility spot checks

In a browser at 320 px width and with the light and dark colour schemes (system setting or the theme toggle):

- no horizontal scrolling on the home page or a guide page;
- navigation reachable by keyboard (Tab, Enter);
- text contrast readable in both schemes (browser accessibility checker, no failures on the home page).

## 5. Read each guide against the code (PR3)

For each guide page: the claims list from the claims check is attached to the PR description; each item points to code or a test. Then follow the Administrator Guide from an empty machine:

```bash
# container route, then standalone route, exactly as the guide says
```

Expected: the server starts, a stock IRC client connects with and without TLS, a reloadable setting changes after reload as documented, and the server stops as documented.

## 6. README

```bash
wc -l README.md
```

Expected: about 100 lines, the badges present, every link to the site or a guide resolves after the cut-over (until then, check them against the local preview paths).

## 7. Workflow behaviour on a pull request (PR 2 and later)

- Open a PR that edits a guide (or the home page): the `build` job runs, `deploy` is skipped.
- Edit `site/package-lock.json` so one `resolved` URL points at another host: `build` fails at the lockfile guard before `npm ci`. Revert.
- `workflow_dispatch` is not offered until the workflow is on the default branch; that check is in scenario 8.

## 8. Cut-over and post-merge checks (maintainer, each step confirmed; right after PR 2 merges, before PR 3)

Before the Pages source is switched to "GitHub Actions", the first `deploy` on `main` fails with a message that Pages is not configured for Actions; this is expected and is the cue for the cut-over. Once switched, a merged edit shows on the public page within 15 minutes.

1. Settings, Pages, source: GitHub Actions. Custom domain `jircd.org` and "Enforce HTTPS" stay set. Settings, Environments, `github-pages`: deployment branches limited to `main`.
2. Re-run the workflow (`workflow_dispatch`).
3. Start the workflow manually (`workflow_dispatch`) from a non-`main` branch: `build` runs, `deploy` is skipped. (GitHub offers the manual run only once the workflow is on the default branch.)
4. `curl -sI https://jircd.org` and `https://www.jircd.org`: HTTP 200 and a valid certificate; the page is the new home page, and `/api/` resolves (guides arrive with PR 3).
5. After a quiet period, push the tag `legacy-gh-pages` at the tip of `gh-pages` (`git tag legacy-gh-pages origin/gh-pages && git push origin legacy-gh-pages`), confirm `git ls-remote --tags origin legacy-gh-pages` shows it, then delete the `gh-pages` branch.

Rollback before step 5: (a) Settings, Environments, `github-pages`: remove the `main`-only deployment branch rule (or allow `gh-pages`), since the branch-based source deploys through that environment too; (b) set the Pages source back to the `gh-pages` branch; (c) `curl -sI` both hosts and check the page. After step 5: recreate the branch with `git push origin legacy-gh-pages:refs/heads/gh-pages`, then (a) to (c).
