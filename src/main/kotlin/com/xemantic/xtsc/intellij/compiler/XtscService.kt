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

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes

/** The name of the file that defines a TypeScript program. */
internal const val TSCONFIG_FILE_NAME = "tsconfig.json"

private val TYPESCRIPT_EXTENSIONS = setOf("ts", "tsx", "mts", "cts")

/**
 * Everything a program may read beyond [TYPESCRIPT_EXTENSIONS]: JavaScript under
 * `allowJs`, and `.json` — importable under `resolveJsonModule`, and the shape of
 * `tsconfig.json`, its `extends` chain and `package.json` besides.
 */
private val PROGRAM_EXTENSIONS = TYPESCRIPT_EXTENSIONS + setOf("js", "jsx", "mjs", "cjs", "json")

internal fun VirtualFile.isTypeScript(): Boolean =
    !isDirectory && extension?.lowercase() in TYPESCRIPT_EXTENSIONS

/**
 * How long a session may sit unqueried before it is closed. Each session owns a thread
 * and a fully built in-memory program, so merely browsing across a monorepo would
 * otherwise accumulate one of each per `tsconfig.json` visited, for the life of the
 * project — nothing but a change on disk would ever let go of them. An evicted session
 * costs its next pass one rebuild, which ten minutes of not looking at it has earned.
 */
private val SESSION_IDLE_TIMEOUT = 10.minutes

/** How often the sessions are swept for ones idle past [SESSION_IDLE_TIMEOUT]. */
private val IDLE_SWEEP_PERIOD = 1.minutes

/**
 * The compiler sessions of one IDE project, one per `tsconfig.json` in play.
 */
@Service(Service.Level.PROJECT)
internal class XtscService(private val project: Project) : Disposable {

    private val sessions = ConcurrentHashMap<String, XtscSession>()

    @Volatile
    private var disposed = false

    /** Whether the unsupported-path refusal has been logged, so that it is logged once. */
    private val unsupportedPathReported = AtomicBoolean()

    private val idleSweep = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
        ::evictIdleSessions,
        IDLE_SWEEP_PERIOD.inWholeMilliseconds,
        IDLE_SWEEP_PERIOD.inWholeMilliseconds,
        TimeUnit.MILLISECONDS,
    )

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) = invalidate(events)
            }
        )
    }

    /**
     * The session for [tsconfigPath], or `null` when there can be none: once this service
     * has been disposed — a build still in flight at project close must not spawn a session
     * nobody will close — or for a path the compiler cannot resolve at all.
     */
    fun session(tsconfigPath: String): XtscSession? {
        if (disposed) return null
        // The compiler's file system treats a path as absolute only when it starts with
        // `/`, so a Windows drive-letter path would be joined onto the JVM's working
        // directory and fail on every pass. Refuse it once and visibly instead;
        // lifting this needs drive-letter support in the compiler's `PathUtil`.
        if (!tsconfigPath.startsWith("/")) {
            if (unsupportedPathReported.compareAndSet(false, true)) {
                thisLogger().warn(
                    "xtsc resolves only `/`-rooted paths, so it cannot open $tsconfigPath" +
                        " and TypeScript highlighting stays off — Windows is not supported yet"
                )
            }
            return null
        }
        val session = sessions.computeIfAbsent(tsconfigPath, ::XtscSession)
        // The service may have been disposed between the check and the insertion.
        if (disposed) {
            sessions.remove(tsconfigPath, session)
            session.close()
            return null
        }
        // Being asked for is being used; without this, a session mid-pass could look
        // idle to the sweep racing it.
        session.lastUsedNanos = System.nanoTime()
        return session
    }

    /** Closes every session that [SESSION_IDLE_TIMEOUT] has passed by; the sweep's tick. */
    internal fun evictIdleSessions() {
        closeSessions { session ->
            System.nanoTime() - session.lastUsedNanos > SESSION_IDLE_TIMEOUT.inWholeNanoseconds
        }
    }

    /**
     * The `tsconfig.json` governing [file] — the nearest one at or above it, without
     * leaving the content root it belongs to. A file outside every content root has no
     * governing `tsconfig.json`: walking up from there would end at the file system root,
     * and a stray `~/tsconfig.json` must not turn the home directory into a program.
     *
     * Requires read access.
     */
    fun tsconfigFor(file: VirtualFile): VirtualFile? {
        val contentRoot = ProjectFileIndex.getInstance(project).getContentRootForFile(file) ?: return null
        var directory = file.parent
        while (directory != null) {
            directory.findChild(TSCONFIG_FILE_NAME)
                ?.takeIf { !it.isDirectory }
                ?.let { return it }
            if (directory == contentRoot) return null
            directory = directory.parent
        }
        return null
    }

    override fun dispose() {
        disposed = true
        idleSweep.cancel(false)
        sessions.values.forEach(XtscSession::close)
        sessions.clear()
    }

    /**
     * Drops the sessions whose view of the world a change on disk has invalidated.
     *
     * A session holds a cached build keyed by nothing but its own edits, so anything that
     * changed behind its back — a `git checkout`, an edited `tsconfig.json` — would
     * otherwise keep being reported from stale text. What a build reads reaches far beyond
     * the directory of its `tsconfig.json`: `files`, `include` and `extends` may point
     * anywhere, and module resolution walks upward for `node_modules` — so rather than
     * guess at each program's reach, a relevant change drops every session, and a dropped
     * session merely costs the next pass a rebuild.
     *
     * The one spared change is a save of a document whose text the compiler already holds
     * as an overlay: dropping the session would throw away a good build. Only that exact
     * case is spared, because an overlay cannot be reverted to disk: a file overlaid while
     * its tab was open and then changed on disk after the tab closed would otherwise be
     * compiled from the old buffer forever, with nothing left to push the new text.
     */
    private fun invalidate(events: List<VFileEvent>) {
        for (event in events) {
            if (sessions.isEmpty()) return
            if (irrelevant(event)) continue
            // A directory born empty holds nothing any program reads; sparing it keeps
            // "new folder" in the Project view, or a tool scaffolding an output
            // directory, from evicting every warm build in the project.
            if (event is VFileCreateEvent && event.isEmptyDirectory) continue
            // A directory event arrives alone — the files inside get no events of their
            // own — so a created, deleted or moved directory may have held anything.
            if (event.isDirectoryEvent) {
                closeSessions { true }
                continue
            }
            val savedText = if (event.isFromSave) event.file?.let(::documentText) else null
            for (path in event.invalidatedPaths()) {
                if (PROGRAM_EXTENSIONS.none { path.endsWith(".$it", ignoreCase = true) }) continue
                closeSessions { session ->
                    savedText == null || savedText != session.overlaidText(path)
                }
            }
        }
    }

    /**
     * Whether every session may ignore [event]: everything it touches sits under an
     * excluded or ignored root — `.git` internals, build output marked off in the
     * project — which no program reads, so no session's view of the world has moved.
     * Without this, a build tool's output churn or plain git activity would evict
     * every warm build a few times a second, exactly while highlighting is wanted.
     *
     * `node_modules` is deliberately NOT spared unless the project excludes it:
     * module resolution reads it, so an `npm install` must drop the sessions.
     *
     * Runs inside the VFS change's write action, which carries the read access
     * [ProjectFileIndex] requires.
     */
    private fun irrelevant(event: VFileEvent): Boolean {
        val file = event.file ?: return false
        // A file already deleted in this batch refuses most questions, but its parent
        // still answers, and a child of an excluded directory is excluded.
        val here = file.takeIf { it.isValid }
            ?: file.parent?.takeIf { it.isValid }
            ?: return false
        val index = ProjectFileIndex.getInstance(project)
        if (!index.isExcluded(here)) return false
        // A move is two places at once; sparing it needs the origin excluded too.
        return event !is VFileMoveEvent || event.oldParent.let { it.isValid && index.isExcluded(it) }
    }

    private inline fun closeSessions(invalidated: (XtscSession) -> Boolean) {
        for ((tsconfigPath, session) in sessions) {
            if (!invalidated(session)) continue
            // Removed by identity: a replacement session put here by a pass racing this
            // event has already seen the change, and must not be evicted over it.
            if (sessions.remove(tsconfigPath, session)) session.close()
        }
    }

    private fun documentText(file: VirtualFile): String? =
        FileDocumentManager.getInstance().getCachedDocument(file)?.text
}

private val VFileEvent.isDirectoryEvent: Boolean
    get() {
        val file = file ?: return true
        return try {
            file.isDirectory
        } catch (_: RuntimeException) {
            // A file deleted in this very batch may refuse the question;
            // whatever it was, assume the worst.
            true
        }
    }

/** The paths [this] touches: its own, and for a move or a rename also the origin. */
private fun VFileEvent.invalidatedPaths(): List<String> = when {
    this is VFileMoveEvent -> listOf(path, oldPath)
    this is VFilePropertyChangeEvent && propertyName == VirtualFile.PROP_NAME -> listOf(path, oldPath)
    else -> listOf(path)
}
