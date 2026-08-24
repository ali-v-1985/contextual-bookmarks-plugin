# Contextual Bookmarks

Contextual Bookmarks is an IntelliJ IDEA plugin for line and mnemonic bookmarks
whose visibility follows the work you are doing. A bookmark can belong to the
whole project, the current branch of one VCS root, or the active IntelliJ
changelist.

> Publication note: the plugin ID `me.a1i.contextualbookmarks` is intended to be
> permanent and uses the `a1i.me` domain identity. The source and issue links currently assume
> `https://github.com/ali-v-1985/contextual-bookmarks-plugin`; confirm that URL
> before creating the Marketplace listing.

<!-- Add Marketplace screenshots here before publishing 0.1.0. -->

## Why a separate bookmark system?

IntelliJ IDEA has a **Restore bookmark context on branch switching** advanced
setting. If branch-only bookmark restoration is all you need, the built-in
feature may be enough. This plugin stays separate from native bookmarks and adds:

- explicit Global, Branch, and Changelist scopes;
- one active view combining Global plus matching Branch and Changelist marks;
- independent mnemonic namespaces for inactive contexts;
- multi-root-aware branch identity and mnemonic resolution;
- a management tool window with inactive and unavailable records.

The plugin never reads, rewrites, hides, imports, migrates, or deletes IDEA's
native bookmarks.

## Scope and visibility

The remembered scope shown in the status bar controls the normal **Toggle
Contextual Bookmark** action. **Add Global Contextual Bookmark** always creates a
Global record.

- **Global** bookmarks are always visible in the project.
- **Branch** bookmarks are visible only while their exact repository root is on
  their stored branch. Detached HEAD and files outside a repository do not
  silently fall back to Global.
- **Changelist** bookmarks are visible only while their stable changelist ID is
  active. Renaming a changelist keeps its bookmarks; deletion leaves repairable
  records in the all-contexts view.

Git, including projects with multiple Git roots, is the tested VCS for 0.1.0.
The implementation uses the generic public DVCS repository API so other DVCS
support can be added without changing the stored model.

## Mnemonics

Mnemonics are `0`–`9` and `A`–`Z`. A mnemonic must be unique inside one exact
scope key, but it can be reused on another branch, repository root, or
changelist. If matching Global/Branch/Changelist records are visible together,
navigation prefers the active editor's repository only when that selects one
record; otherwise the plugin asks you to choose.

No native bookmark shortcut is replaced. Open **Settings | Keymap**, search for
“Contextual Bookmarks,” and bind the toggle, popup, next/previous, or digit
mnemonic actions as desired. In particular, F11, Ctrl+F11, Shift+F11, and
Ctrl+digit remain untouched by default.

## Tool window and repair

Open **View | Tool Windows | Contextual Bookmarks**, use **Find Action**, or use
the **Tools | Contextual Bookmarks** group. The active view shows Global plus
matching Branch and Changelist bookmarks. Clear **Active contexts only** to see
inactive/missing contexts and unavailable locations.

Range markers follow edits while a file is open. Before saves and context
changes, the plugin records the current file URL, zero-based position, and
normalized hashes of the current and neighboring lines. After restart it checks
the stored line and then searches at most 200 lines in either direction. Missing
or non-unique results are reported instead of causing a speculative jump; use
**Relink** at the desired caret to repair one.

## Data and privacy

Bookmark state is personal project workspace data stored through IntelliJ's
workspace storage with roaming disabled. For directory-based projects, this is
commonly `.idea/workspace.xml`, which may contain bookmark URLs and descriptions
and should remain ignored and private. The plugin creates no separate committed
project data file, makes no network requests, and does not sync bookmarks between
machines or people. Export/import is outside the 0.1.0 scope.

## Compatibility and installation

Version 0.1.0 compiles against IntelliJ IDEA 2025.3.4 / build 253 and declares
compatibility through build 262.*. The configured verifier resolves IDEA
2026.1.3 to build 261 and IDEA 2026.2.0.1 to build 262. Plugin bytecode targets
Java 21; IDEA 2026.2 itself runs on its required Java 25 runtime.

To install a local build, choose **Settings | Plugins | gear icon | Install Plugin
from Disk** and select the ZIP produced under `build/distributions`.

## Development

Use the checked-in Gradle wrapper. These commands have been run successfully in
this repository:

```bash
./gradlew test --tests 'me.a1i.contextualbookmarks.model.*' --tests 'me.a1i.contextualbookmarks.navigation.BookmarkLocatorTest' --tests 'me.a1i.contextualbookmarks.persistence.*'
./gradlew test --tests 'me.a1i.contextualbookmarks.context.*' --tests 'me.a1i.contextualbookmarks.service.*'
./gradlew test --tests 'me.a1i.contextualbookmarks.editor.BookmarkPositionTrackerTest'
./gradlew verifyPluginProjectConfiguration verifyPluginStructure
./gradlew buildPlugin
./gradlew verifyPlugin
```

The `runIde` task launches the development sandbox. The local startup log
confirmed that Contextual Bookmarks 0.1.0 loaded, but the downloaded IDEA
distribution then initiated its own bundled-plugin update/restart and caused the
Gradle task to exit with code 2, so it is not listed as a verified command above.
Signing uses only the `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`, and
`CERTIFICATE_CHAIN` environment variables. Publishing additionally requires
`PUBLISH_TOKEN`; do not run `publishPlugin` without intentionally authorizing a
Marketplace release.

## Current limitations

- Git is the only VCS exercised for 0.1.0.
- State is local to one IDE project workspace and does not roam.
- Native IDEA bookmarks are intentionally not imported.
- Marketplace vendor/trader details, the permanent repository URL, screenshots,
  and a beta/hidden listing must be confirmed manually before publication.

Problems can be reported through the provisional
[GitHub issue tracker](https://github.com/ali-v-1985/contextual-bookmarks-plugin/issues).

## License

Apache License 2.0. See [LICENSE](LICENSE).
