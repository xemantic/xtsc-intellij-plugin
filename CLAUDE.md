# CLAUDE.md

This file captures only what cannot be inferred from the codebase itself.

## Rules for editing this file

Both developers and AI agents are expected to add entries as they encounter surprises.

- **Add an entry** when you encounter something unexpected: a build quirk, a non-obvious constraint, a dependency gotcha, or any behavior that would surprise the next agent or developer.
- **Add an entry** when a developer flags an anti-pattern produced by AI — describe the anti-pattern and the preferred alternative.
- **Do not** add codebase overviews, directory listings, or anything discoverable by reading the source.
- Keep entries concise: one line per lesson, grouped under a heading if a theme emerges.

## Conventions

### Markdown authoring

Markdown files use [semantic line breaks](https://sembr.org/):
break a line after a sentence,
and optionally at clause boundaries within a long sentence,
so that diffs stay meaningful and reviewable.

There is no column width limit —
never reflow or hard-wrap a paragraph to fit some character count.
Modern editors soft-wrap Markdown visually,
see the [README](README.md#markdown-soft-wrapping-in-the-ide) for how to enable it.

## Known gotchas

- After upgrading the Gradle wrapper, `jvmTest` may fail with `NoSuchFileException: build/test-results/jvmTest/binary/in-progress-results-generic.bin`, because the results of the previous Gradle version are stale — delete `build/test-results` (or run `clean`) and retry.
- The first `verifyPlugin` downloads and unpacks a full IntelliJ IDEA Ultimate distribution into the Gradle transforms cache — it runs for many minutes with no output and looks hung, while `check` and `buildPlugin` finish in about a minute. Later runs are fast.
- The plugin `<name>` in `plugin.xml` is the JetBrains Marketplace listing name, which must be title case, 1–4 words, at most 20 characters, and must not contain "Plugin", "Support", "Tool", "Integration", "JetBrains" or a product name — hence the bare `xtsc` rather than the repository name.
- The plugin `<id>` deliberately does not mirror the Gradle group and Kotlin package: `verifyPlugin` splits the id on `.` and rejects any component that is a JetBrains product name, `intellij` included (`idea` is allowed). Only `check` and `buildPlugin` pass with such an id — the failure surfaces in `verifyPlugin` alone, and the id is permanent once published.

## Embedding the xtsc compiler

- The compiler is resolved from **mavenLocal** when a locally built copy is present
  (`./gradlew publishToMavenLocal` in a sibling checkout of [xemantic-typescript-compiler](https://github.com/xemantic/xemantic-typescript-compiler)),
  and otherwise from the SNAPSHOT its main branch publishes to Maven Central — which is all a CI runner has.
- The compiler's file system treats only `/`-rooted paths as absolute,
  so the plugin refuses Windows drive-letter paths rather than failing silently on every pass;
  supporting Windows means teaching the compiler's `PathUtil` about drive letters first.
- The compiler's kotlinx runtime must stay relocated by the `shadedCompiler` task —
  the IDE bundles copies of its own that are incompatible in both directions,
  and the task's KDoc in [build.gradle.kts](build.gradle.kts) tells the full story.
  Do not "simplify" this back to a plain `implementation` dependency:
  both failure modes are `NoSuchMethodError` at runtime,
  and only one of them shows up in tests.
- `filesMatching { duplicatesStrategy }` on a `ShadowJar` task is silently ignored —
  merging a duplicated entry needs a Shadow transformer.
- Without `bundledPlugin("intellij.libraries.misc.plugin")` in the test dependencies,
  2026.2's plugin resolution excludes the spell checker and then **this plugin**,
  so no extension point is registered and every highlighting test passes vacuously.
  Check for `Loaded custom plugins: xtsc` in the test sandbox's `idea.log`
  before believing a green highlighting test.
- The platform's thread-leak check excuses a parked coroutine worker by its exact class name,
  which the relocation renames — so the compiler's idle dispatcher threads read as leaks
  in whichever test the scheduler happens to grow a worker in,
  and the failure moves between runs without looking like a shading problem.
  `ShadedDispatcherThreads` excuses them by thread name instead.

## Why the plugin targets IntelliJ 2026.2 and nothing older

The compiler publishes **Kotlin 2.4 metadata**,
and the target IDE's bundled Kotlin has to be able to read it.
From `intellij-community`'s own `.idea/kotlinc.xml`, per release branch:

| IDEA | bundled Kotlin |
|---|---|
| 2025.2 | 2.2.0 |
| 2026.1 | 2.3.20 |
| 2026.2 | 2.4.0 |

2026.2 is the first release that can.
Re-derive the table from that file before changing `intellijIdea(...)`.
The compiler's bytecode targets Java 21,
so the JBR version is not a constraint and the Gradle build runs on a plain JDK 21.

## Anti-patterns to avoid

- Do not add content to this file that is already discoverable by reading the source or build scripts — that inflates context without adding signal, reducing AI agent task success rates (see [arxiv 2602.11988](https://arxiv.org/abs/2602.11988)).
