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

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.VfsTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.testFramework.runInEdtAndWait
import com.xemantic.kotlin.test.assert
import com.xemantic.xtsc.intellij.ShadedDispatcherThreads
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.time.Duration.Companion.minutes

/**
 * The service's contracts: which paths it refuses, and above all WHEN a change on disk
 * costs a session — the invalidation rules of [XtscService.invalidate] — since a wrong
 * answer there shows up to the user as errors reported from text that no longer exists.
 *
 * Sessions are compared by identity: the service hands out the same instance until
 * something invalidates it, so `!==` after an event IS the observation that it dropped.
 * Most tests never build anything — a session is created lazily, so a made-up
 * `tsconfig.json` path yields a real session whose eviction can be watched for free.
 *
 * [ShadedDispatcherThreads] also excuses the sessions' own `xtsc: <path>` threads,
 * whose fire-and-forget close may outlive a test.
 */
@TestApplication
@ExtendWith(ShadedDispatcherThreads::class)
class XtscServiceTest {

    private val projectDir = tempPathFixture()

    private val project = projectFixture(projectDir, openAfterCreation = true)

    /** The content root; without it nothing in the project can be marked excluded. */
    private val module = project.moduleFixture(projectDir, addPathToSourceRoot = true)

    private val fakeTsconfig = "/no/such/project/tsconfig.json"

    private fun service(): XtscService = project.get().service<XtscService>()

    private fun root(): VirtualFile = VfsUtil.findFile(projectDir.get(), true)!!

    @Test
    fun `Should refuse a tsconfig path the compiler cannot resolve`() {
        // The compiler treats only `/`-rooted paths as absolute; a Windows drive-letter
        // path must be refused up front instead of failing on every highlighting pass.
        assert(service().session("C:/project/tsconfig.json") == null)
    }

    @Test
    fun `Should hand out the same session until something invalidates it`() {
        val service = service()
        val session = service.session(fakeTsconfig)
        assert(session != null)
        assert(service.session(fakeTsconfig) === session)
    }

    @Test
    fun `Should drop sessions when a TypeScript file appears on disk`() {
        val service = service()
        val session = service.session(fakeTsconfig)

        VfsTestUtil.createFile(root(), "src/appeared.ts", "export const a = 1;\n")

        assert(service.session(fakeTsconfig) !== session)
    }

    @Test
    fun `Should keep sessions when a file no program reads changes`() {
        val service = service()
        val session = service.session(fakeTsconfig)

        VfsTestUtil.createFile(root(), "notes.txt", "nothing a program reads\n")

        assert(service.session(fakeTsconfig) === session)
    }

    @Test
    fun `Should keep sessions when an empty directory is created`() {
        val service = service()
        val session = service.session(fakeTsconfig)

        // "new folder" in the Project view, a tool scaffolding an output directory:
        // a directory born empty holds nothing any program reads
        VfsTestUtil.createDir(root(), "scaffold")

        assert(service.session(fakeTsconfig) === session)
    }

    @Test
    fun `Should drop sessions when a directory is deleted`() {
        val doomed = VfsTestUtil.createDir(root(), "doomed")
        val service = service()
        val session = service.session(fakeTsconfig)

        // deleted with no events for whatever it held, so it may have held anything
        VfsTestUtil.deleteFile(doomed)

        assert(service.session(fakeTsconfig) !== session)
    }

    @Test
    fun `Should keep sessions for changes under an excluded root`() {
        val excluded = VfsTestUtil.createDir(root(), "out")
        PsiTestUtil.addExcludedRoot(module.get(), excluded)
        val service = service()
        val session = service.session(fakeTsconfig)

        // build-output churn lands in excluded directories, several times a second
        VfsTestUtil.createFile(excluded, "generated.ts", "export const g = 1;\n")

        assert(service.session(fakeTsconfig) === session)
    }

    @Test
    fun `Should evict a session idle past the timeout`() {
        val service = service()
        val session = service.session(fakeTsconfig)!!

        session.lastUsedNanos = System.nanoTime() - 11.minutes.inWholeNanoseconds
        service.evictIdleSessions()

        assert(service.session(fakeTsconfig) !== session)
    }

    @Test
    fun `Should keep a recently used session through the idle sweep`() {
        val service = service()
        val session = service.session(fakeTsconfig)

        service.evictIdleSessions()

        assert(service.session(fakeTsconfig) === session)
    }

    @Test
    fun `Should keep the session when a save writes text the compiler already holds`() {
        // given
        // a real program, because the overlay records text only once a build accepted it
        val tsconfig = VfsTestUtil.createFile(
            root(),
            "tsconfig.json",
            """{ "compilerOptions": { "strict": true, "noEmit": true }, "include": ["src"] }""",
        )
        val source = VfsTestUtil.createFile(root(), "src/edited.ts", "const x: number = 1;\n")
        val service = service()
        val session = service.session(tsconfig.path)!!
        val edited = "const x: number = 2;\n"
        session.diagnostics(mapOf(source.path to BufferContent(edited, 1)), source.path)
        assert(session.overlaidText(source.path) == edited)

        // when
        // the user hits save: the document writes to disk the very text the compiler holds
        runInEdtAndWait {
            val documents = FileDocumentManager.getInstance()
            val document = documents.getDocument(source)!!
            runWriteAction { document.setText(edited) }
            documents.saveDocument(document)
        }

        // then
        // dropping the session here would throw away a good build after every save
        assert(service.session(tsconfig.path) === session)
    }

    @Test
    fun `Should drop the session when a file changes behind the compiler's back`() {
        // given
        val tsconfig = VfsTestUtil.createFile(
            root(),
            "tsconfig.json",
            """{ "compilerOptions": { "strict": true, "noEmit": true }, "include": ["src"] }""",
        )
        val source = VfsTestUtil.createFile(root(), "src/checkout.ts", "const x: number = 1;\n")
        val service = service()
        val session = service.session(tsconfig.path)!!
        session.diagnostics(mapOf(source.path to BufferContent("const x: number = 2;\n", 1)), source.path)

        // when
        // not a save of the document — a `git checkout`, an external tool
        runInEdtAndWait {
            runWriteAction { VfsUtil.saveText(source, "const x: string = \"three\";\n") }
        }

        // then
        assert(service.session(tsconfig.path) !== session)
    }
}
