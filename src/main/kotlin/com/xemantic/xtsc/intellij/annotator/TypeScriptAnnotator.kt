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

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.xml.util.XmlStringUtil
import com.xemantic.typescript.compiler.Diagnostic
import com.xemantic.typescript.compiler.DiagnosticCategory
// Context-sensitive resolution cannot pick this one out on its own: `Error` is
// ambiguous with the default-imported `kotlin.Error`, and an explicit import is
// what outranks it.
import com.xemantic.typescript.compiler.DiagnosticCategory.Error
import com.xemantic.xtsc.intellij.compiler.BufferContent
import com.xemantic.xtsc.intellij.compiler.TSCONFIG_FILE_NAME
import com.xemantic.xtsc.intellij.compiler.XtscService
import com.xemantic.xtsc.intellij.compiler.isTypeScript
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val LOG = logger<TypeScriptAnnotator>()

/**
 * Shows the errors xtsc reports for a TypeScript file, against the editor's buffer
 * rather than what is on disk.
 *
 * Registered for [com.intellij.lang.Language.ANY] because the plugin does not depend
 * on the IDE having a TypeScript language of its own: a `.ts` file is recognised by
 * its extension, and the compiler is asked about it whatever the platform parsed it as.
 */
internal class TypeScriptAnnotator : ExternalAnnotator<TypeScriptAnnotator.Request, List<Diagnostic>>() {

    /** What [doAnnotate] needs, read off the EDT while the document is still committed. */
    internal class Request(
        val project: Project,
        val tsconfigPath: String,
        val filePath: String,
        /** Path to current text, for every buffer the next build should see. */
        val buffers: Map<String, BufferContent>,
    )

    /** Whether the stand-down before the bundled engine has been logged, so it is logged once. */
    private val bundledEngineReported = AtomicBoolean()

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Request? =
        collect(file, includeOpenBuffers = true)

    override fun collectInformation(file: PsiFile): Request? =
        collect(file, includeOpenBuffers = false)

    override fun doAnnotate(request: Request?): List<Diagnostic>? {
        if (request == null) return null
        val diagnostics = request.project.service<XtscService>()
            .session(request.tsconfigPath)
            ?.diagnostics(request.buffers, request.filePath)
        // An IDE that ships TypeScript support of its own reports it in the very same
        // `TS<code>: <message>` shape, so the two are indistinguishable on screen and
        // the log is the only place that says whose errors these are.
        LOG.debug { "xtsc answered about ${request.filePath}: ${diagnostics?.size ?: "nothing"}" }
        return diagnostics
    }

    override fun apply(file: PsiFile, diagnostics: List<Diagnostic>?, holder: AnnotationHolder) {
        if (diagnostics.isNullOrEmpty()) return
        val fileLength = file.textLength
        val filePath = file.virtualFile?.path
        for (diagnostic in diagnostics) {
            // The query rides the config's path along with the file's, so a diagnostic
            // may belong to `tsconfig.json` rather than to the file on screen — and its
            // span then points into THAT file, never into this one.
            val foreign = diagnostic.fileName != null && diagnostic.fileName != filePath
            if (foreign || diagnostic.start == null) {
                // A foreign diagnostic, or one without a span — a broken `tsconfig.json`,
                // a bad compiler option, an empty `include` — concerns the file as a
                // whole, not any character in it.
                val message = if (foreign) {
                    "${diagnostic.fileName!!.substringAfterLast('/')}: ${diagnostic.codedMessage}"
                } else {
                    diagnostic.codedMessage
                }
                holder.newAnnotation(diagnostic.category.severity, message)
                    .tooltip(diagnostic.tooltip(message))
                    .range(TextRange(0, fileLength))
                    .fileLevel()
                    .create()
                continue
            }
            // The buffer may have moved on between the build and this call; a span that
            // no longer fits the file is stale and dropped, not clamped somewhere wrong.
            // Settled before `newAnnotation`: a builder abandoned without `create()` is
            // a `PluginException` that aborts the whole highlighting pass.
            val range = diagnostic.rangeIn(fileLength) ?: continue
            holder.newAnnotation(diagnostic.category.severity, diagnostic.codedMessage)
                .tooltip(diagnostic.tooltip(diagnostic.codedMessage))
                .range(range)
                .create()
        }
    }

    private fun collect(file: PsiFile, includeOpenBuffers: Boolean): Request? {
        val virtualFile = file.virtualFile ?: return null
        if (!virtualFile.isTypeScript()) return null
        if (bundledEngineActive()) return null
        val project = file.project
        val service = project.service<XtscService>()
        val tsconfig = service.tsconfigFor(virtualFile) ?: run {
            LOG.debug { "no $TSCONFIG_FILE_NAME governs ${virtualFile.path}" }
            return null
        }

        val buffers = LinkedHashMap<String, BufferContent>()
        if (includeOpenBuffers) {
            // Every open TypeScript tab, whichever `tsconfig.json` it sits under: the
            // compiler must see the current text of each of them, or this file would be
            // checked against the stale on-disk text of a neighbour it imports. Scoping
            // this to tabs governed by the same config would guess at the program's
            // reach and guess wrong — `files`, `include` and `extends` may cross a
            // nested config's boundary. A buffer the program never reads is an unused
            // overlay entry; only a change to one costs a rebuild it did not need.
            val documents = FileDocumentManager.getInstance()
            for (openFile in FileEditorManager.getInstance(project).openFiles) {
                if (!openFile.isTypeScript()) continue
                val document = documents.getDocument(openFile) ?: continue
                buffers[openFile.path] = document.cachedContent()
            }
        }
        // The annotated file need not be open in an editor at all; text read off the PSI
        // rather than a document carries version 0, which never overrides a buffer.
        val content = FileDocumentManager.getInstance().getDocument(virtualFile)?.cachedContent()
            ?: BufferContent(file.text, 0)
        buffers.putIfAbsent(virtualFile.path, content)
        return Request(project, tsconfig.path, virtualFile.path, buffers)
    }

    /**
     * Whether the IDE's own "JavaScript and TypeScript" plugin is enabled. It reports
     * every error this annotator would, in the very same `TS<code>: <message>` shape, so
     * running both would underline everything twice — xtsc stands down instead, and says
     * so once in the log. Disabling the bundled plugin is what hands `.ts` files to xtsc.
     */
    private fun bundledEngineActive(): Boolean {
        val javascriptPluginId = PluginId.getId("JavaScript")
        if (PluginManagerCore.getPlugin(javascriptPluginId) == null) return false
        if (PluginManagerCore.isDisabled(javascriptPluginId)) return false
        if (bundledEngineReported.compareAndSet(false, true)) {
            LOG.info(
                "the bundled JavaScript and TypeScript plugin is enabled, so xtsc leaves" +
                    " TypeScript highlighting to it — disable that plugin to see xtsc's diagnostics"
            )
        }
        return true
    }
}

private val CACHED_CONTENT = Key.create<CachedDocumentContent>("xtsc.cached.document.text")

private class CachedDocumentContent(val stamp: Long, val content: BufferContent)

/**
 * Ever-growing source of [BufferContent.version]: read under the read action that also
 * reads the document's text, so a snapshot of OLDER text always draws a SMALLER number
 * than any snapshot of text the document moved on to — the order the session relies on.
 */
private val bufferVersions = AtomicLong()

/**
 * [Document.getText] copies the whole buffer on every call, and every highlighting pass
 * asks about every open tab — so the copy is kept on the document itself, keyed by its
 * modification stamp, and a pass over unchanged tabs allocates nothing. Handing the SAME
 * instance to the session each time is also what lets its "has the compiler already seen
 * this text" check answer by reference instead of comparing the characters.
 */
private fun Document.cachedContent(): BufferContent {
    val stamp = modificationStamp
    getUserData(CACHED_CONTENT)?.let { if (it.stamp == stamp) return it.content }
    return BufferContent(text, bufferVersions.incrementAndGet())
        .also { putUserData(CACHED_CONTENT, CachedDocumentContent(stamp, it)) }
}

/**
 * The span to underline, or `null` when the buffer has shrunk past it since the build.
 *
 * [Diagnostic.start] is documented upstream as a byte offset, but the compiler emits
 * Kotlin string indices — UTF-16 code units, exactly what [TextRange] expects — so the
 * values pass through untranslated. The annotator test with an emoji before the error
 * is what pins that: it fails the moment either side changes its unit.
 */
private fun Diagnostic.rangeIn(fileLength: Int): TextRange? {
    val from = (start ?: return null).coerceAtLeast(0)
    if (from > fileLength) return null
    val to = (from + (length ?: 0)).coerceAtMost(fileLength)
    if (to > from) return TextRange(from, to)
    // A zero-width span underlines nothing; widen it to the character it sits on,
    // or to the one before it when the span is at end of file.
    if (fileLength == 0) return null
    return if (from < fileLength) TextRange(from, from + 1) else TextRange(from - 1, from)
}

/** The one-line form: [Diagnostic.message] prefixed with the code it was reported under. */
private val Diagnostic.codedMessage get() = "TS$code: $message"

private fun Diagnostic.tooltip(firstLine: String): String {
    val lines = buildList {
        add(firstLine)
        addAll(messageChain)
        relatedInformation.mapTo(this) { related ->
            val where = related.fileName?.substringAfterLast('/')?.let { name ->
                related.line?.let { "$name:$it: " } ?: "$name: "
            }.orEmpty()
            "$where${related.message}"
        }
    }
    return XmlStringUtil.wrapInHtml(
        lines.joinToString("<br>") { XmlStringUtil.escapeString(it) }
    )
}

private val DiagnosticCategory.severity: HighlightSeverity get() = when (this) {
    Error -> ERROR
    Warning -> WARNING
    // Neither maps onto a visible severity of its own: an INFORMATION annotation
    // renders nothing unless it carries text attributes.
    Message -> WEAK_WARNING
    Suggestion -> WEAK_WARNING
}
