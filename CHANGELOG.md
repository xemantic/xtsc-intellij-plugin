<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# xtsc-intellij-plugin Changelog

## [Unreleased]
### Added
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
- Apache License 2.0
- TypeScript errors, reported by the embedded `xtsc` compiler,
  highlighted in `.ts`, `.tsx`, `.mts` and `.cts` files against the editor's buffer rather than what is on disk
- An unreadable or malformed `tsconfig.json` reported as a file-level error in the files it governs,
  instead of a silently clean editor over a program checked with default options
- A diagnostic belonging to another file — `tsconfig.json` above all —
  reported file-level and named after its true home,
  never underlined at that file's offsets inside the file on screen
- The annotator stands down while the IDE's bundled "JavaScript and TypeScript" plugin is enabled,
  because both engines report the same errors in the same `TS<code>: <message>` shape
  and running both would underline everything twice
- Compiler sessions idle for ten minutes are closed,
  so merely browsing across a monorepo no longer accumulates a thread
  and a whole-program build per `tsconfig.json` visited
- Creating an empty directory no longer evicts every warm build,
  and a failed compiler update no longer freezes diagnostics on stale text

### Changed
- Gradle group and Kotlin source package moved from `com.github.morisil.xtscintellijplugin` to `com.xemantic.xtsc.intellij`
- Plugin id set to `com.xemantic.xtsc`, which cannot mirror the package because a plugin id may not contain a JetBrains product name
- Plugin name, vendor and description describe the `xtsc` integration instead of the template placeholders
- Target platform raised to IntelliJ IDEA 2026.2,
  the first release whose bundled Kotlin (2.4) can read the metadata the compiler publishes
