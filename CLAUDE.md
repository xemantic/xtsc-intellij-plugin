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

## Anti-patterns to avoid

- Do not add content to this file that is already discoverable by reading the source or build scripts — that inflates context without adding signal, reducing AI agent task success rates (see [arxiv 2602.11988](https://arxiv.org/abs/2602.11988)).
