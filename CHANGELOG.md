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

### Changed
- Gradle group and Kotlin source package moved from `com.github.morisil.xtscintellijplugin` to `com.xemantic.xtsc.intellij`
- Plugin id set to `com.xemantic.xtsc`, which cannot mirror the package because a plugin id may not contain a JetBrains product name
- Plugin name, vendor and description describe the `xtsc` integration instead of the template placeholders
- Target platform raised to IntelliJ IDEA 2026.2,
  the first release whose bundled Kotlin (2.4) can read the metadata the compiler publishes
