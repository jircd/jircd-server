# Feature Specification: Project Site

**Feature Branch**: `017-project-site`

**Created**: 2026-11-18

**Status**: Draft

**Input**: User description: "Replace the 2010 legacy GitHub Pages site (one auto-generated page at jircd.org) with a modern project site that is the project's front page. The README becomes a short quick guide; the details move to documentation guides on the site. Javadoc for the extension API is published with it."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A visitor understands the project and starts a server (Priority: P1)

Someone follows the project's public address (`jircd.org`), or the link in the repository description, and wants to know what JIRCD is, whether it is ready to use, and how to try it. Today they get a single auto-generated page from 2010 that says nothing current.

**Why this priority**: The front page is the first contact with the project. Replacing it is the reason for the feature, and it delivers value even before any guide exists.

**Independent Test**: Open the site's home page on a clean browser profile. Within two minutes, with no other source, state what the project is, what is distinctive about it, its current status, and start a server by copy-pasting the quick start.

**Acceptance Scenarios**:

1. **Given** a visitor on the home page, **When** they read it, **Then** they find a description of the project, what is distinctive (a modular core with extensions that can be switched on and off while the server runs), the current status, a quick start, and links to the repository, the guides and the API reference.
2. **Given** a visitor who types `jircd.org` or `www.jircd.org`, **When** the page loads, **Then** it is the new site, served over HTTPS with a valid certificate, and no content of the 2010 page remains.
3. **Given** a visitor on a phone, **When** they open any page, **Then** the page is readable without horizontal scrolling and the navigation is reachable.
4. **Given** a visitor who prefers a dark colour scheme, **When** they open the site, **Then** the site follows that preference and stays readable.

---

### User Story 2 - An IRC user connects with a client (Priority: P2)

A person who has an address of a JIRCD server wants to connect, use TLS, know which commands and IRCv3 capabilities the server supports, and know the channel modes.

**Why this priority**: These are the most frequent questions after "what is it", and they are currently buried in a 600-line README that mixes in operator and developer content.

**Independent Test**: A reader who has never used the project follows only the User Guide and connects a stock IRC client to a running server, with and without TLS, and finds the supported command, capability and channel-mode lists.

**Acceptance Scenarios**:

1. **Given** the User Guide, **When** a reader looks for how to connect, **Then** it explains the connection settings for a client, how to use TLS, and what to do on the first connection.
2. **Given** the guide's lists of commands, IRCv3 capabilities and channel modes, **When** compared with the server's behaviour, **Then** every listed item exists and no implemented item of those kinds is missing.

---

### User Story 3 - An administrator installs and runs a server (Priority: P2)

An operator wants to install the server (container image or standalone build), configure it, and run it day to day: logging, reload and stop, protection of the OPER command, connection limits, the registration timeout, the message of the day and admin contact, the listener backlog, generating an administrator credential and the outbound queue (SendQ) policy.

**Why this priority**: Operators are the main audience of the project and already have this content, but only inside the README. Moving it onto the site, organised by task, is the largest part of the content work.

**Independent Test**: Starting from an empty machine, follow only the Administrator Guide to run the server from the container image and from a standalone build, change one setting from each topic, reload, and stop it.

**Acceptance Scenarios**:

1. **Given** the Administrator Guide, **When** an operator follows the installation steps for either route, **Then** the server starts and accepts a client connection.
2. **Given** every configuration key the server reads, **When** compared with the configuration reference, **Then** each is documented with its default and its effect, and no documented key is unknown to the server.
3. **Given** the operator changes a reloadable setting, **When** they follow the guide's reload steps, **Then** the change applies as the guide says.

---

### User Story 4 - A developer builds, tests and extends the server (Priority: P3)

A developer wants to build and test the project, understand the module layout, write a capability provider against the extension API, and find how to contribute. They also want browsable API documentation for the extension API.

**Why this priority**: Developers are fewer than users and operators, and the contributor workflow already lives in `CONTRIBUTING.md`; the site mainly makes the extension API discoverable.

**Independent Test**: A developer follows the Developer Guide to build and test the project, then writes a minimal capability provider using only the guide and the API reference, and loads it in a server.

**Acceptance Scenarios**:

1. **Given** the Developer Guide, **When** a developer follows the build and test instructions, **Then** they run the same checks the project's continuous integration runs.
2. **Given** the API reference, **When** a developer looks up a type named in the capability provider guide, **Then** it has generated documentation, and the reference covers the extension API only, not the whole server.
3. **Given** the Developer Guide's contributing section, **When** a reader follows its link, **Then** they reach `CONTRIBUTING.md` for the rules, which remains the single source.

---

### User Story 5 - A maintainer publishes the site safely (Priority: P2)

A maintainer wants every change to the documentation or the extension API to reach the public site automatically after it merges, and wants a broken page or link to fail the pull request instead of reaching the public site.

**Why this priority**: Without automated publishing the site goes stale, as the 2010 page did. Safe previews keep the guides accurate.

**Independent Test**: Open a pull request that breaks a link; its check fails and nothing is published. Merge a pull request that edits a guide; the public page shows the edit without a manual step.

**Acceptance Scenarios**:

1. **Given** a pull request that changes the site, the guides or the extension API sources, **When** its checks run, **Then** the site and the API reference are built, links are checked, and a broken build or link fails the pull request without publishing.
2. **Given** a change merged into the main branch that touches the site, the guides or the extension API sources, **When** the checks finish, **Then** the public site is updated with no manual step.
3. **Given** a maintainer who wants to republish without a code change, **When** they start the publishing run by hand, **Then** it publishes the current main branch.
4. **Given** the first successful automated publication, **When** the maintainer confirms it, **Then** the legacy page's source branch is retired and the custom address and HTTPS keep working throughout.

---

### User Story 6 - The README is a short quick guide (Priority: P3)

A person who lands on the repository's page wants a short README: what the project is, how to start it, how to connect, and where the guides are.

**Why this priority**: The README stops being the manual once the site holds the guides; leaving 600 lines in both places would let them diverge.

**Independent Test**: Read the README alone and start a server with a client connected; follow each link to the matching guide page.

**Acceptance Scenarios**:

1. **Given** the README, **When** a reader opens it, **Then** it is about 100 lines, keeps the project badges, and holds the description, a quick start for the container and for a standalone build, how to connect, and links to the site and guides.
2. **Given** content that moved out of the README, **When** a reader looks for it, **Then** it exists exactly once, in a guide page, with nothing lost that was accurate.

---

### Edge Cases

- A guide page references another page that has moved or been renamed: the link check fails the pull request.
- A visitor follows an old link into the repository's README sections: the section's content is reachable through the guides, and the README links to each guide.
- The documentation build fails, or the publishing service is unavailable: the previously published site stays up, unchanged.
- A pull request from a fork has no secrets: the site and link checks still run, since they need none.
- A guide describes a setting or capability that the code no longer has: the claims check before each pull request catches it; the pull request does not merge with known inaccuracies.
- The Javadoc build finds an error in a documented type: the build fails the pull request, as the rest of the project's build does.
- A visitor with scripts disabled: reading the guides and navigating keep working.
- The custom address stops resolving to the site after the source switch: the cut-over steps keep the old page up until the new deployment is verified, and the cut-over is reversible.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The project MUST have a public website served at `jircd.org` (and `www.jircd.org`) over HTTPS with a valid certificate, replacing the legacy page.
- **FR-002**: The home page MUST present: the project description, what is distinctive (modular core with runtime-toggleable extensions), the current status, a quick start, and links to the repository, the guides and the API reference.
- **FR-003**: The site MUST have a User Guide covering connecting with a client, TLS, supported commands, IRCv3 capabilities and channel modes.
- **FR-004**: The site MUST have an Administrator Guide covering: installing by container or standalone build, the configuration reference, logging, reload and stop, OPER protection, connection limits, the registration timeout, the message of the day and admin contact, the listener backlog, generating administrator credentials, and the SendQ policy.
- **FR-005**: The site MUST have a Developer Guide covering: build and test, the module layout, writing a capability provider (SPI), and a link to `CONTRIBUTING.md` for contributing.
- **FR-006**: The site MUST publish an API reference at `/api/`, generated from the source, covering only the extension API (the types a capability provider or server extension author uses). Identifying exactly which packages form that API is part of planning.
- **FR-007**: The guide sources MUST be Markdown files in a top-level `docs/` directory so they remain readable on GitHub, and MUST be the single copy of that content; the home page is the one exception in format (`docs/index.mdx`, MDX, in the same directory).
- **FR-008**: The README MUST shrink to a quick guide of about 100 lines that keeps the badges and links to the site and the guides; content it gives up MUST move to a guide, not disappear.
- **FR-009**: Every factual claim in the guides (commands, capabilities, modes, configuration keys and defaults, behaviours) MUST be checked against the code and tests before the pull request that publishes it.
- **FR-010**: Pull requests that change the site, the guides or the extension API sources MUST build the site and the API reference and check internal links; a failure MUST fail the pull request and MUST NOT publish.
- **FR-011**: Changes merged to the main branch that touch the site, the guides or the extension API sources MUST publish automatically; a manual run MUST also be possible; only runs on the main branch deploy (a manual run from another branch builds only).
- **FR-012**: The publishing workflow MUST use the platform's official upload and deploy actions, pin every third-party action by full commit SHA, and pin the tool runtime version; Dependabot MUST watch the site's dependencies.
- **FR-013**: Switching the Pages source from the legacy branch to the workflow, and retiring the legacy branch, MUST be explicit, maintainer-confirmed steps; the switch happens right after the pull request that adds the workflow, home page and API reference merges (not after the guides), and the legacy branch is retired after a quiet period, keeping the custom domain and HTTPS.
- **FR-014**: The site MUST be usable on narrow screens, follow the visitor's light or dark preference, offer page search, and keep a consistent navigation across the four sections, with the project's brand colour and logo and no further custom design.
- **FR-015**: The feature MUST NOT regress the existing build: `./gradlew build` (tests, Spotless, SpotBugs, PMD, coverage floor, dependency verification), SonarCloud and CI keep passing. The Node documentation tool chain MUST be required only by the documentation job and for local preview, never by the server build; the API reference's Javadoc task needs only the JDK and is part of `./gradlew build`, so a broken Javadoc comment in the published packages fails CI and local builds.
- **FR-016**: Delivery MUST be split into stacked pull requests, each passing the build on its own and staying near 120,000 diff characters (Sourcery's limit is 150,000), the lockfile being kept out of the review bots by path filters (a CodeRabbit path filter in the repository, the Sourcery Path Filter in its dashboard): spec documents and bot filters; site skeleton, home page, publishing workflow and API reference; guides and README rewrite.

### Key Entities

- **Guide page**: a Markdown document in `docs/` belonging to one section (User, Administrator, Developer), with a title and an order in its section's navigation.
- **Site**: the generated website: home page, the three guide sections, the API reference and shared navigation, search and theme.
- **API reference**: generated documentation of the extension API packages, published under `/api/`.
- **Publication**: a deployment of the site produced by the workflow, either automatic after a merge or started by hand.
- **Legacy page**: the 2010 single-page site on the `gh-pages` branch with the `jircd.org` custom domain; retired after the cut-over.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A first-time visitor can say what the project is, what is distinctive and its status, from the home page alone, in under 2 minutes.
- **SC-002**: A first-time operator starts a working server by following only the Administrator Guide, with no step needing outside knowledge, in under 15 minutes.
- **SC-003**: 100% of configuration keys, commands, IRCv3 capabilities and channel modes the server implements appear in the guides, and 0 documented ones are unknown to the server, as confirmed by the claims check.
- **SC-004**: 0 broken internal links on the published site, enforced on every pull request.
- **SC-005**: After a merge touching the guides, the public site shows the change within 15 minutes without a manual step.
- **SC-006**: The README is about 100 lines and every topic it drops has exactly one home in the guides.
- **SC-007**: Each of the three pull requests passes `./gradlew build` and stays under 150,000 diff characters (target 120,000).
- **SC-008**: Site pages meet a basic accessibility and responsiveness bar: readable at 320 px width, text contrast sufficient in both colour schemes, keyboard-navigable.
- **SC-009**: The cut-over causes no outage of `jircd.org` longer than the time for the platform to switch sources, and HTTPS stays valid.

## Assumptions

- The site's visitors are IRC users, operators and developers, in that order of frequency; English only.
- The generator is Astro Starlight; Zensical was rejected as too new (0.1.0) and Docusaurus as heavier than needed (the choice is recorded in `research.md`).
- Versioned documentation is not needed: the project is `0.1.0-SNAPSHOT` with no release yet.
- Blog, translations and custom design beyond the theme, brand colour and logo are out of scope.
- GitHub Pages remains the host, with the existing `jircd.org` custom domain.
- Tracking issue: #159. The home page lives with the guides in `docs/`.
- The API reference covers the extension API only (`net.jircd.core.extension`, `net.jircd.core.extension.capability`, `net.jircd.protocol`, plus only what links need to resolve); the rest of the server's internals are not published as Javadoc.
- `CONTRIBUTING.md`, `SECURITY.md` and the spec-kit documents under `specs/` stay where they are and are linked, not copied.
- The README keeps its current badges.
- Pages source switching and `gh-pages` retirement need repository administrator rights and are done by the maintainer.

## Out of Scope

- Versioned documentation, a blog, translations, a custom design system.
- Javadoc for the whole server.
- Changing server behaviour, configuration or extension API (documenting only; inaccuracies found go to separate issues).
- Hosting other than GitHub Pages.
