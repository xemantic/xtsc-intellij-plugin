# xtsc-intellij-plugin

TypeScript code insight for IntelliJ-based IDEs,
backed by [xtsc](https://github.com/xemantic/xemantic-typescript-compiler) —
the TypeScript compiler rewritten from scratch in Kotlin.

![Build](https://github.com/xemantic/xtsc-intellij-plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

[<img alt="license" src="https://img.shields.io/github/license/xemantic/xtsc-intellij-plugin?color=blue">](https://github.com/xemantic/xtsc-intellij-plugin/blob/main/LICENSE)
[<img alt="GitHub last commit" src="https://img.shields.io/github/last-commit/xemantic/xtsc-intellij-plugin">](https://github.com/xemantic/xtsc-intellij-plugin/commits/main/)
[<img alt="discord users online" src="https://img.shields.io/discord/811561179280965673">](https://discord.gg/vQktqqN2Vn)
[![Bluesky](https://img.shields.io/badge/Bluesky-0285FF?logo=bluesky&logoColor=fff)](https://bsky.app/profile/xemantic.com)

> [!WARNING]
> Early development.
> Error highlighting works;
> everything else below is still the scaffold generated from the [IntelliJ Platform Plugin Template][template],
> and the plugin has not been published to JetBrains Marketplace.

## What works

Errors reported by `xtsc` are highlighted in `.ts`, `.tsx`, `.mts` and `.cts` files
that sit under a `tsconfig.json`,
against the editor's buffer rather than what is on disk —
so an error appears as you type it, without saving.

The compiler runs in the IDE process.
Because it is built against Kotlin 2.4 metadata and kotlinx-coroutines 1.11,
the plugin requires **IntelliJ IDEA 2026.2 or newer**
and relocates the compiler's `kotlinx` packages away from the ones the IDE bundles;
see [CLAUDE.md](CLAUDE.md) for why each of those is load-bearing.

## Why?

Every TypeScript-aware editor talks to `tsserver`:
a Node.js process on the other side of a protocol boundary,
serializing questions out and answers back.

`xtsc` is a whole-program TypeScript type checker written in Kotlin,
and its API is an ordinary function call —
open a project, push unsaved editor buffers into it, ask it questions:

```kotlin
project.updateFile(path, editorBuffer)  // never touches disk
project.quickInfoAt(file, offset)       // hover
project.definitionsAt(file, offset)     // go to definition
project.completionsAt(file, offset)     // completions
```

Running on the JVM, it can be embedded directly in the IDE process.
This plugin is that embedding:
no Node.js runtime, no `tsserver` child process, no language server protocol in between.

## Planned capabilities

- Whole-program type checking driven by `tsconfig.json`
- Hover, go to definition, find usages, completion and signature help
- Rename that is re-checked before it is applied
- Diagnostics matching those reported by `tsc`

## Installation

Once the plugin is published to JetBrains Marketplace:

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "xtsc"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/xemantic/xtsc-intellij-plugin/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

## Development

```shell
./gradlew runIde        # launch a sandbox IDE with the plugin installed
./gradlew check         # compile and run the tests
./gradlew verifyPlugin  # plugin structure checks and the IntelliJ Plugin Verifier
./gradlew buildPlugin   # produce the distributable ZIP in build/distributions
```

The same three tasks are also available as the `Run Plugin`, `Run Tests` and `Run Verifications`
run configurations shipped in [.run](.run).

Every change goes through the `Build` workflow, which builds, tests, verifies,
and refreshes a draft GitHub release.
Publishing that draft triggers the `Release` workflow,
which signs the plugin, uploads it to JetBrains Marketplace,
and opens a pull request moving the `Unreleased` section of [CHANGELOG.md](CHANGELOG.md)
under the released version.

### Markdown soft-wrapping in the IDE

Markdown sources here use [semantic line breaks](https://sembr.org/) and are never hard-wrapped,
so long paragraphs read best with soft wrapping enabled:

<kbd>Settings/Preferences</kbd> > <kbd>Editor</kbd> > <kbd>General</kbd> > <kbd>Soft Wraps</kbd> >
check <kbd>Soft-wrap these files</kbd> and make sure the file mask includes `*.md`.

## Before the first Marketplace release

These steps need a human with the relevant accounts, and are not done yet:

- [ ] Review the [Legal Agreements](https://plugins.jetbrains.com/docs/marketplace/legal-agreements.html?from=IJPluginTemplate).
- [ ] Add a plugin icon at `src/main/resources/META-INF/pluginIcon.svg` (and a dark variant, `pluginIcon_dark.svg`).
- [ ] [Publish the plugin manually](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html?from=IJPluginTemplate) for the first time — Marketplace will not accept an automated upload of a plugin it has never seen.
- [ ] Replace `MARKETPLACE_ID` in the badges and installation links above with the ID assigned at publication.
- [ ] Set the [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html?from=IJPluginTemplate) secrets — `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` and `CERTIFICATE_CHAIN` — in <kbd>Settings</kbd> > <kbd>Secrets and variables</kbd> > <kbd>Actions</kbd>.
- [ ] Set the [Deployment Token](https://plugins.jetbrains.com/docs/marketplace/plugin-upload.html?from=IJPluginTemplate) as the `PUBLISH_TOKEN` secret.
- [ ] Enable <kbd>Read and write permissions</kbd> in <kbd>Settings</kbd> > <kbd>Actions</kbd> > <kbd>General</kbd> > <kbd>Workflow permissions</kbd>, so the `Release` workflow can open the changelog pull request.
- [ ] Click <kbd>Watch</kbd> on the [IntelliJ Platform Plugin Template][template] to be notified about releases containing new features and fixes.

## License

Copyright 2026 Kazimierz Pogoda / Xemantic

Licensed under the [Apache License, Version 2.0](LICENSE).

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
