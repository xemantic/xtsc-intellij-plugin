import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.jengelman.gradle.plugins.shadow.transformers.AppendingTransformer
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.Coordinates
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.power-assert")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
    id("com.gradleup.shadow")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        // Lets a name be resolved against the type expected at its position, so that an
        // enum entry or a constant of the expected type needs no qualifier. Still behind
        // a flag in Kotlin 2.4.
        freeCompilerArgs.add("-Xcontext-sensitive-resolution")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

// Every assertion function of xemantic-kotlin-test that takes a `Boolean`, so that a
// failure prints the expression with the value of each of its parts, rather than "false".
@OptIn(ExperimentalKotlinGradlePluginApi::class)
powerAssert {
    functions = listOf(
        "kotlin.assert",
        "com.xemantic.kotlin.test.assert",
        "com.xemantic.kotlin.test.have",
    )
}

/**
 * The TypeScript compiler and the kotlinx runtime it was built against, held apart from
 * the plugin's own classpath so that [shadedCompiler] can relocate them.
 */
val compiler = configurations.create("compiler")

/**
 * The compiler, with its kotlinx dependencies moved out of the way.
 *
 * The IDE bundles its own, and the two are not interchangeable in either direction: the
 * platform's service container calls `runBlockingWithParallelismCompensation`, which only
 * its patched build of kotlinx-coroutines has, while the compiler calls coroutines 1.11's
 * `runBlockingK`, which the platform's 1.10 does not have. Relocating the compiler's copy
 * is what lets both run in one process. Nothing in the compiler's public API mentions
 * these packages, so the rename is invisible to the plugin's own code — which is why the
 * plugin compiles against the plain artifacts and runs against this jar.
 */
val shadedCompiler = tasks.register<ShadowJar>("shadedCompiler") {
    group = "build"
    description = "Packages the xtsc compiler with its kotlinx runtime relocated out of the IDE's way."
    archiveClassifier = "compiler"
    configurations = listOf(compiler)
    // The whole `kotlinx` prefix rather than the packages the compiler happens to pull
    // today (coroutines, serialization, io): the compiler is consumed as a moving
    // SNAPSHOT, and a kotlinx runtime it grows tomorrow would otherwise land unshaded
    // beside the IDE's incompatible copy. `kotlin-stdlib` is excluded from the
    // configuration, so everything `kotlinx.*` in here is the compiler's to relocate.
    relocate("kotlinx", "com.xemantic.xtsc.intellij.shaded.kotlinx")
    // Shadow's Kotlin-module and service-file transformers have to see every copy of a
    // duplicated path in order to merge it; the default EXCLUDE drops them beforehand.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    // ...while the Plugin Verifier rejects a JAR with a duplicate entry outright, and both
    // compiler modules carry a `META-INF/LICENSE`. Concatenating them keeps the notices
    // and leaves one entry.
    transform(AppendingTransformer::class.java) { resource = "META-INF/LICENSE" }
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    // A relocated jar is not a module, and six copies of a module descriptor
    // are six duplicate entries.
    exclude("module-info.class", "META-INF/versions/*/module-info.class")
}

// The plugin distribution is assembled by the IntelliJ Platform plugin, so Shadow's own
// fat jar of this project has nothing to do here.
tasks.named<ShadowJar>("shadowJar") { enabled = false }

configurations {
    compileOnly { extendsFrom(compiler) }
    testCompileOnly { extendsFrom(compiler) }
}

dependencies {

    compiler("com.xemantic.typescript:xemantic-typescript-compiler-project:0.1.0-SNAPSHOT") {
        // The IDE provides the Kotlin standard library, and every plugin shares it.
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }

    runtimeOnly(files(shadedCompiler))

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // `CodeInsightTestFixtureImpl` reports its own failures through JUnit 4's `Assert`, so
    // junit4 stays on the runtime classpath even though no test here is written against it.
    testRuntimeOnly("junit:junit:4.13.2")

    testImplementation("com.xemantic.kotlin:xemantic-kotlin-test:1.17.5")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2026.2.1")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
        // `codeInsightFixture`, the JUnit 5 wrapper around `CodeInsightTestFixture`, ships
        // in a module that `TestFrameworkType` does not name — and that class is sealed, so
        // the coordinates are given directly. The version defaults to the one closest to
        // the platform resolved above, exactly as for the two frameworks above it.
        testPlatformDependency(
            Coordinates("com.jetbrains.intellij.platform", "test-framework-junit5-code-insight"),
        )
        // Without it the platform's spell checker does not resolve, and 2026.2's plugin
        // resolution then excludes this plugin from the test IDE entirely — so the
        // annotator is never registered and every highlighting test passes vacuously.
        bundledPlugin("intellij.libraries.misc.plugin")
    }
}

tasks.test {
    useJUnitPlatform()
}

// The IDEs this plugin runs in bundle TypeScript support of their own, which reports its
// errors in the same `TS<code>: <message>` shape as xtsc does — so the editor alone never
// says which engine put a squiggle on screen. This turns the annotator's own trace on, and
// the sandbox log then names every file xtsc was actually asked about.
tasks.withType<RunIdeTask>().configureEach {
    jvmArgs("-Didea.log.debug.categories=#com.xemantic.xtsc")
}

// ...and this takes the other engine out of the sandbox altogether, so that whatever is
// underlined in a `.ts` file there came from xtsc. `JavaScript` is the id of the bundled
// "JavaScript and TypeScript" plugin; everything built on it — Angular, Vue, React,
// Prettier, ESLint, Next.js, Vite, NodeJS — is dropped with it by the platform's own
// dependency resolution, and none of it is disabled in the IDE this build runs from.
//
// The property is the only source of `disabled_plugins.txt`: whatever is not listed here
// is re-enabled the next time this task runs, including anything turned off by hand in
// the sandbox's own plugin settings.
tasks.prepareSandbox {
    disabledPlugins.addAll(
        "com.intellij.modules.ultimate",
        "JavaScript",
    )
}
