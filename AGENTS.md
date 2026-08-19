# AGENTS.md

## Scope

These instructions apply to the entire repository.

## Repository State

This project is not scaffolded yet. At the time this file was created, the
repository contained only IDE metadata and a root `.gitignore`; there were no
source files, build scripts, tests, or documented development commands.

Do not assume a build system, language, IntelliJ Platform version, or plugin ID
until those choices are represented in tracked project files.

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
