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

package com.xemantic.xtsc.intellij.annotator

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.testFramework.runInEdtAndWait
import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import com.xemantic.xtsc.intellij.ShadedDispatcherThreads
import com.xemantic.xtsc.intellij.compiler.TSCONFIG_FILE_NAME
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * The whole path, from a `.ts` file in the editor to an error stripe in it.
 *
 * The project is a real directory rather than the in-memory VFS, because the compiler
 * crawls the project through its own file system. Every fixture here is an instance
 * property, so each test gets its own project — the compiler types one `tsconfig.json`
 * as a single program, and files left behind by an earlier test would share a global
 * scope with the file under test.
 */
@TestApplication
@ExtendWith(ShadedDispatcherThreads::class)
class TypeScriptAnnotatorTest {

    private val projectDir = tempPathFixture()

    private val project = projectFixture(projectDir, openAfterCreation = true)

    /**
     * Nothing here names the module, but without it the project has no source root, the
     * `.ts` file under test falls outside it, and all three tests see no highlighting at
     * all — [codeInsightFixture] itself resolves the project's first module.
     */
    private val module = project.moduleFixture(projectDir, addPathToSourceRoot = true)

    private val fixture by codeInsightFixture(project, projectDir).dependsOn(module)

    @BeforeEach
    fun writeTsconfig() {
        fixture.tempDirFixture.createFile(
            "tsconfig.json",
            """{ "compilerOptions": { "strict": true, "noEmit": true }, "include": ["src"] }""",
        )
    }

    @Test
    fun `Should highlight an error over the offending expression`() {
        // given
        val source = "const x: number = \"not a number\";\n"
        val file = fixture.tempDirFixture.createFile("src/a.ts", source)
        fixture.configureFromExistingVirtualFile(file)

        // when
        val errors = fixture.doHighlighting(HighlightSeverity.ERROR)

        // then
        assert(errors.size == 1)
        errors[0] should {
            have(description.startsWith("TS2322:"))
            have(source.substring(startOffset, endOffset) == "x")
        }
    }

    @Test
    fun `Should not highlight a clean file`() {
        // given
        val file = fixture.tempDirFixture.createFile("src/clean.ts", "export const y: number = 1;\n")

        // when
        fixture.configureFromExistingVirtualFile(file)
        val errors = fixture.doHighlighting(HighlightSeverity.ERROR)

        // then
        assert(errors.isEmpty())
    }

    @Test
    fun `Should place the error span correctly after a non-ASCII character`() {
        // given
        // an emoji is 1 code point, 2 UTF-16 units and 4 UTF-8 bytes, so only offsets in
        // UTF-16 units — what the editor expects — put the span on `x`; the compiler's
        // KDoc promises byte offsets but emits Kotlin string indices, and this is the
        // test that fails the moment either side changes its unit
        val source = "const emoji = \"😀\";\nconst x: number = \"bad\";\n"
        val file = fixture.tempDirFixture.createFile("src/emoji.ts", source)
        fixture.configureFromExistingVirtualFile(file)

        // when
        val errors = fixture.doHighlighting(HighlightSeverity.ERROR)

        // then
        assert(errors.size == 1)
        errors[0] should {
            have(description.startsWith("TS2322:"))
            have(source.substring(startOffset, endOffset) == "x")
        }
    }

    @Test
    fun `Should report a tsconfig error against the file as a whole`() {
        // given
        // the tsconfig written by `writeTsconfig` is spoiled after the fact
        val tsconfig = fixture.tempDirFixture.getFile("tsconfig.json")!!
        runInEdtAndWait {
            runWriteAction { VfsUtil.saveText(tsconfig, "{ this is not JSON") }
        }
        val source = "export const ok: number = 1;\n"
        val file = fixture.tempDirFixture.createFile("src/governed.ts", source)
        fixture.configureFromExistingVirtualFile(file)

        // when
        val errors = fixture.doHighlighting(HighlightSeverity.ERROR)

        // then
        // the diagnostic belongs to `tsconfig.json`, so nothing may be underlined at its
        // offsets inside this file — it is reported file-level, named after its true home
        assert(errors.isNotEmpty())
        errors should {
            have(all { it.description.startsWith("$TSCONFIG_FILE_NAME: TS") })
            have(all { it.startOffset == 0 && it.endOffset == source.length })
        }
    }

    @Test
    fun `Should report what was typed into the buffer instead of what is on disk`() {
        // given
        val file = fixture.tempDirFixture.createFile("src/typed.ts", "const x: number = 1;\n")
        fixture.configureFromExistingVirtualFile(file)
        assert(fixture.doHighlighting(HighlightSeverity.ERROR).isEmpty())

        // when
        // nothing is saved, so an error found here can only come from the buffer
        fixture.performEditorAction(IdeActions.ACTION_EDITOR_TEXT_END)
        fixture.type("const y: string = 5;\n")

        // then
        val errors = fixture.doHighlighting(HighlightSeverity.ERROR)
        assert(errors.size == 1)
    }
}
