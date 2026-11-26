# Security Policy

## Supported Versions

JIRCD is in pre-release. Supported versions will be listed here once stable
releases begin. See the [project site](https://jircd.org/) for current project status.

| Version | Supported |
| ------- | --------- |
| N/A (pre-release) | — |

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub
issues.**

If you believe you've found a security vulnerability in JIRCD, report it
privately using one of these channels:

1. **[GitHub Security Advisories](../../security/advisories/new)**
   (preferred) — lets you disclose privately and coordinate a fix.
2. Email **contact@jircd.org** with details.

Please include as much of the following as you can:

- A description of the vulnerability and its potential impact
- Steps to reproduce, or a proof-of-concept
- The affected version/commit, if known

You should expect an acknowledgment within a few days. This is a
volunteer-maintained project, so response and fix timelines aren't
guaranteed, but security reports are prioritized over other work.

## Scope

Given the project's nature (an IRC server accepting untrusted network
input), reports involving the following are especially relevant:

- Protocol parsing issues (malformed input causing crashes, memory
  exhaustion, or unexpected behavior)
- Authentication/authorization bypass (once authentication is
  implemented — see `spec.md`'s deferred Story 3)
- Administrator-privilege escalation (the in-band `OPER`/`EXTENSION`
  commands, once implemented)
- Denial-of-service vectors beyond what the documented rate-limiting is
  designed to handle
