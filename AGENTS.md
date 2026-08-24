# AGENTS.md

## Scope

These instructions apply to the entire repository.

## Repository State

This is a Kotlin/JVM IntelliJ Platform plugin built with the Gradle wrapper. The
permanent plugin ID is `me.a1i.contextualbookmarks`; production sources use
`me.a1i.contextualbookmarks` under the `me.a1i` Gradle group. The compile target
is IntelliJ IDEA 2025.3.4 / build 253. Kotlin targets JVM 21, the Gradle build
runs on Java 25 LTS, and the descriptor is capped at build 262.* so the configured
verifier covers IDEA 2025.3.4, 2026.1.3, and 2026.2.1.

Bookmark state is schema-versioned, private project workspace data with roaming
disabled. Treat its DTO fields and scope identity as compatibility-sensitive.

## Working Guidelines

- Inspect the repository before changing it and preserve unrelated user work.
- Keep changes focused on the requested task; avoid opportunistic refactors.
- Prefer the existing project structure and conventions once they exist.
- Do not commit generated output, IDE workspace state, credentials, signing
  material, or locally specific configuration.
- Add or update tests for behavior changes when a test setup is available.
- Run the narrowest relevant verification first, then the broader project checks.
- Report which checks were run and call out anything that could not be verified.

## IntelliJ Plugin Conventions

Once the plugin is scaffolded:

- Use the repository's Gradle wrapper rather than a system Gradle installation.
- Keep the plugin descriptor, declared dependencies, and target IntelliJ Platform
  compatibility aligned.
- Use IntelliJ Platform APIs instead of internal implementation classes whenever
  a supported public API exists.
- Treat persistent bookmark data and settings as compatibility-sensitive. Make
  migrations explicit when stored formats change.
- Keep UI work on the Event Dispatch Thread and move blocking or expensive work
  off it using the platform's supported APIs.
- Dispose listeners, services, and other resources according to IntelliJ Platform
  lifecycle rules.
- Add tests around bookmark creation, navigation, persistence, and project or IDE
  lifecycle behavior as those features are introduced.

## Documentation Maintenance

Update this file when the project gains concrete build, test, formatting, or run
commands. Document only commands that are present in the repository and have been
verified locally.

## Verified Commands

Use the checked-in wrapper. The following commands have succeeded locally:

```bash
./gradlew test --tests 'me.a1i.contextualbookmarks.model.*' --tests 'me.a1i.contextualbookmarks.navigation.BookmarkLocatorTest' --tests 'me.a1i.contextualbookmarks.persistence.*'
./gradlew test --tests 'me.a1i.contextualbookmarks.context.*' --tests 'me.a1i.contextualbookmarks.service.*'
./gradlew test --tests 'me.a1i.contextualbookmarks.editor.BookmarkPositionTrackerTest'
./gradlew check
./gradlew buildPlugin
./gradlew verifyPluginProjectConfiguration verifyPluginStructure
./gradlew verifyPlugin
```

`verifyPlugin` is configured for the three concrete IDE releases above. Signing
and publishing require environment-only secrets and have not been run locally.
