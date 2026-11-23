---
title: Build and test
description: Build JIRCD, run tests and checks, and format Java code.
sidebar:
  order: 1
---

JIRCD uses the Gradle wrapper and a Java 25 toolchain. Run commands from the repository root.

## Build and test

| Command | What it does |
| --- | --- |
| `./gradlew test` | Runs the regular tests; load and environment-specific tests are excluded. |
| `./gradlew :<module>:test --tests '<Class or pattern>'` | Runs a selected test class or pattern in one module. |
| `./gradlew build` | Runs the tests and verification checks, including formatting, static analysis, coverage, and extension API Javadoc. |
| `./gradlew loadTest` | Runs tests tagged `load`; use when a change affects performance budgets. |
| `./gradlew testCodeCoverageReport` | Writes the aggregate coverage report. |

The build enforces at least 85% line coverage and 75% branch coverage across production modules. Container tests need a container runtime; native metadata tests need a GraalVM JDK, so the regular `test` task excludes them as well as `load` tests.

## Formatting and hooks

Run `./gradlew spotlessApply` to format Java sources. Enable the repository's pre-commit formatting check with `git config core.hooksPath .githooks`; it runs `spotlessCheck`. The build also runs SpotBugs and PMD.

## Dependency verification

Gradle dependency verification uses the trusted keys and checksums in [`gradle/verification-keyring.keys`](https://github.com/jircd/jircd-server/blob/main/gradle/verification-keyring.keys) and [`gradle/verification-metadata.xml`](https://github.com/jircd/jircd-server/blob/main/gradle/verification-metadata.xml). Key-server lookups are disabled. If a dependency update fails verification, follow the repository's [dependency verification instructions](https://github.com/jircd/jircd-server/blob/main/AGENTS.md#commands) to verify and add only the required publisher key or artifact checksum.
