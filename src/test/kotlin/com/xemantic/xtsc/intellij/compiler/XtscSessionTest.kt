/*
 * Copyright 2026 Kazimierz Pogoda / Xemantic
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xemantic.xtsc.intellij.compiler

import com.intellij.testFramework.junit5.TestApplication
import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import com.xemantic.typescript.compiler.DiagnosticCategory
import com.xemantic.xtsc.intellij.ShadedDispatcherThreads
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Exercises the embedded compiler itself: that the IDE's JVM can load it, that it
 * reads a project off disk, and that an editor buffer overrides what is on disk.
 *
 * The project lives in a real temporary directory rather than in the IDE's VFS, because
 * the compiler reads through its own `SystemVfs`. Nothing here opens a file in an editor,
 * so [TestApplication] — an application-level container and nothing more — is the whole
 * environment these tests need.
 */
@TestApplication
@ExtendWith(ShadedDispatcherThreads::class)
class XtscSessionTest {

    private lateinit var root: Path
    private var session: XtscSession? = null

    @BeforeEach
    fun setUp() {
        root = Files.createTempDirectory("xtsc-plugin-test")
        root.resolve("tsconfig.json").writeText(
            """{ "compilerOptions": { "strict": true, "noEmit": true }, "include": ["src"] }""",
        )
        root.resolve("src").createDirectories()
    }

    @AfterEach
    fun tearDown() {
        // `close` hands the project's own close to the session's thread and returns;
        // the directory is only removed once that has run.
        session?.close()?.get()
        root.toFile().deleteRecursively()
    }

    private fun session(): XtscSession =
        XtscSession(root.resolve("tsconfig.json").toString()).also { session = it }

    private fun sourcePath(name: String) = root.resolve("src").resolve(name).toString()

    @Test
    fun `Should report a type error read from disk`() {
        // given
        val path = sourcePath("a.ts")
        Path.of(path).writeText("const x: number = \"not a number\";\n")

        // when
        val diagnostics = session().diagnostics(emptyMap(), path)

        // then
        diagnostics should {
            val errors = filter { it.category == DiagnosticCategory.Error }
            assert(errors.size == 1)
            errors[0] should {
                have(code == 2322)
                have(start != null)
                have(length != null)
            }
        }
    }

    @Test
    fun `Should let an unsaved buffer override the file on disk`() {
        // given
        val path = sourcePath("b.ts")
        Path.of(path).writeText("const x: number = 1;\n")
        val session = session()
        session.diagnostics(emptyMap(), path) should {
            have(none { it.category == DiagnosticCategory.Error })
        }

        // when
        // nothing is written to disk; the compiler sees only the overlay
        val diagnostics = session.diagnostics(mapOf(path to "const x: number = true;\n"), path)

        // then
        diagnostics should {
            val errors = filter { it.category == DiagnosticCategory.Error }
            assert(errors.size == 1)
            have(errors[0].code == 2322)
        }
        assert(Path.of(path).readText() == "const x: number = 1;\n")
    }

    @Test
    fun `Should answer null once closed and tolerate a second close`() {
        // given
        val path = sourcePath("closed.ts")
        Path.of(path).writeText("const x: number = 1;\n")
        val session = session()
        session.diagnostics(emptyMap(), path)

        // when
        session.close()?.get()

        // then
        assert(session.close() == null)
        assert(session.diagnostics(emptyMap(), path) == null)
    }

    @Test
    fun `Should report a malformed tsconfig against the file being checked`() {
        // given
        root.resolve("tsconfig.json").writeText("{ this is not JSON")
        val path = sourcePath("c.ts")
        Path.of(path).writeText("const x: number = 1;\n")

        // when
        val diagnostics = session().diagnostics(emptyMap(), path)

        // then
        // the config error has no span — the annotator shows it file-level
        diagnostics should {
            val errors = filter { it.category == DiagnosticCategory.Error }
            assert(errors.size == 1)
            errors[0] should {
                have(code == 5014)
                have(start == null)
            }
        }
    }

    @Test
    fun `Should scope diagnostics to the queried file`() {
        // given
        val broken = sourcePath("broken.ts")
        val clean = sourcePath("clean.ts")
        Path.of(broken).writeText("const x: number = \"nope\";\n")
        Path.of(clean).writeText("export const y: number = 1;\n")
        val buffers = listOf(broken, clean).associateWith { Path.of(it).readText() }

        // when
        val diagnostics = session().diagnostics(buffers, clean)

        // then
        diagnostics should {
            have(none { it.category == DiagnosticCategory.Error })
        }
    }
}
