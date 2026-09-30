# Quality gate

The repository uses three layers of checks: pull request checks, validation of the
merged `master` commit, and an isolated release gate. A check only blocks a merge
when it is also configured as required in the GitHub repository ruleset.

## Local gate

Run the same deterministic checks used by CI with the checked-in wrapper:

```bash
./gradlew check
./gradlew verifyPluginProjectConfiguration verifyPluginStructure
./gradlew buildPlugin verifyPlugin
scripts/verify-plugin-archive.sh
scripts/test-verify-plugin-archive.sh
go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.12
```

`check` includes ktlint formatting through Spotless, all tests, HTML/XML Kover
reports, and a 77% minimum line-coverage rule. Use `./gradlew spotlessApply` to
apply the configured formatting before rerunning the gate.

Actionlint checks workflow syntax, expressions, job dependencies, and embedded
shell syntax. Actionlint v1.7.12 declares Go 1.25.0; CI pins Go 1.26.x.

Plugin Verifier checks the three IDE versions declared in the Gradle build. The
archive check requires one unsigned ZIP, limits it to 20 MiB, and rejects source
and test directories, Kotlin/Java source files, IntelliJ workspace files,
environment files, and signing keys and certificates.

## Pull request checks

The following workflows run for pull requests targeting `master`:

- **Build / Quality gate** — formatting, tests, coverage, plugin structure,
  packaging, archive contents, and Plugin Verifier.
- **Qodana / Static analysis** — the recommended inspection profile; any critical
  finding or increase above the accepted baseline of seven high findings fails
  the workflow.
- **Dependency Review / Review new dependencies** — rejects newly introduced high
  or critical vulnerabilities in runtime, development, or unknown scopes. A
  read-only PR workflow generates the resolved head dependency snapshot; a
  trusted `workflow_run` job downloads and submits it without checking out or
  executing pull-request code.
- **CodeQL / Analyze Kotlin** — security-extended Java/Kotlin analysis.

Qodana lower-severity findings remain annotations rather than blockers. Ratchet
the high-severity threshold down as the accepted findings are fixed. CodeQL
uploads findings to GitHub; repository code-scanning protection must be
configured to reject high or critical alerts.

## Merged `master` checks

Build, Qodana, and CodeQL rerun on the exact merged commit. The build uploads the
verified plugin ZIP and reports for 14 days. Dependency Submission publishes the
resolved Gradle graph so Dependabot and Dependency Review can include transitive
build and runtime dependencies. Dependency snapshot jobs disable Gradle's
configuration cache so graph extraction always observes dependency resolution.
Build and CodeQL also run weekly to detect environment and analyzer changes
without a source commit; CodeQL disables Gradle's build cache and reruns Kotlin
compilation so its manual-build database cannot be satisfied from cached classes.

A post-merge failure cannot undo a merge. It blocks release promotion and needs
to be investigated before another release tag is created.

## Repository settings

Configure a branch ruleset for `master` with:

1. Pull requests required before merge.
2. Required status checks for the four pull request checks listed above.
3. Code scanning results required with high and critical alerts blocked.
4. Required conversation resolution.
5. Force pushes and branch deletion disabled.
6. Code owner review for workflow and quality-gate configuration when another
   maintainer is available.

Enable Dependency Graph, Dependabot alerts, secret scanning, and push protection.
The `marketplace` environment should require manual approval and limit deployment
to protected `v*` tags. Configure `QODANA_TOKEN`, `PRIVATE_KEY`,
`PRIVATE_KEY_PASSWORD`, `CERTIFICATE_CHAIN`, and `PUBLISH_TOKEN` as repository or
environment secrets as appropriate.

If a merge queue is enabled later, add the `merge_group` trigger to every required
workflow before making the queue mandatory.

## Release gate

Create the release tag only after its version is present in `gradle.properties`
and has a matching changelog section. A `v*` tag starts the release workflow. To
retry an existing tag manually, dispatch the workflow with that tag as both the
workflow ref and the validated input:

```bash
gh workflow run release.yml --ref vX -f tag=vX
```

A dispatch from `master` with only `tag=vX` fails before entering the protected
`marketplace` environment because environment tag policies evaluate the workflow
run ref, not the later checkout ref.

The release workflow:

1. Requires the tagged commit to be reachable from `master` and waits for
   successful Build, Qodana, and CodeQL push runs for that exact commit.
2. Requires the tag, Gradle version, and changelog version to match.
3. Runs a clean quality gate, package build, structural checks, and all configured
   Plugin Verifier targets.
4. Checks the unsigned archive, signs it, and verifies the signature.
5. Generates a SHA-256 checksum and GitHub build-provenance attestation.
6. Creates the GitHub Release without replacing an existing asset. A retry is
   accepted only when the existing signed ZIP is byte-for-byte identical.
7. Publishes that signed artifact to JetBrains Marketplace as a hidden update.

Unhide the Marketplace update only after JetBrains approval and a clean-install
smoke test of the uploaded version.
