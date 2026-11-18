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
cd site && npm ci && npm run build
cp -R ../build/docs/extension-api dist/api
npm run preview
```

Expected: the build passes with link validation; the preview serves the home page, the three guide sections with sidebar and search, and `/api/`.

## 3. Prove the link check fails

Add `[x](./does-not-exist.md)` to a guide, run `npm run build`.

Expected: the build fails and names the file and the link. Revert the edit.

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

## 7. Workflow behaviour (after PR2 merges)

- Open a PR that edits a guide: the `build` job runs, `deploy` is skipped.
- Start the workflow manually (`workflow_dispatch`) from a non-`main` branch: `build` runs, `deploy` is skipped.
- Merge it: `deploy` runs; the public page shows the edit within 15 minutes.
- Before the Pages source is switched to "GitHub Actions", the first `deploy` fails with a message that Pages is not configured for Actions; this is expected and is the cue for the cut-over.

## 8. Cut-over (maintainer, each step confirmed; right after PR 2 merges, before PR 3)

1. Settings, Pages, source: GitHub Actions. Custom domain `jircd.org` and "Enforce HTTPS" stay set. Settings, Environments, `github-pages`: deployment branches limited to `main`.
2. Re-run the workflow (`workflow_dispatch`).
3. `curl -sI https://jircd.org` and `https://www.jircd.org`: HTTP 200 and a valid certificate; the page is the new home page, and `/api/` resolves (guides arrive with PR 3).
4. After a quiet period, delete the `gh-pages` branch after recording its last commit hash in the cut-over task.

Rollback before step 4: set the Pages source back to the `gh-pages` branch.
