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

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.xemantic.typescript.compiler.Diagnostic
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import com.xemantic.typescript.compiler.project.Project as XtscProject

/** How long a caller waits on a build before checking whether it was cancelled. */
private const val POLL_MILLIS = 50L

/**
 * One `tsconfig.json`, the [XtscProject] compiling it, and the single thread that owns them.
 *
 * An [XtscProject] is not thread-safe and its builds run synchronously on the calling
 * thread, so every call is funnelled through one executor.
 */
internal class XtscSession(private val tsconfigPath: String) {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xtsc: $tsconfigPath").apply { isDaemon = true }
    }

    /** Owned by [executor]; other threads only look at whether it is set. */
    @Volatile
    private var project: XtscProject? = null

    /**
     * The text last handed to the compiler, per path. Written on [executor] only, but
     * readable from any thread so that [XtscService] can tell a save of text the compiler
     * already has from a change made behind its back.
     */
    private val overlay = ConcurrentHashMap<String, String>()

    /** The text the compiler currently holds for [path], or `null` if it reads it from disk. */
    fun overlaidText(path: String): String? = overlay[path]

    private val closed = AtomicBoolean()

    /** Whether the current streak of failures has been reported; a success resets it. */
    private val failureReported = AtomicBoolean()

    /**
     * The diagnostics of [filePath], with every buffer in [buffers] applied first.
     *
     * Returns `null` when the compiler could not answer — an unreadable
     * `tsconfig.json`, or a session that has already been closed.
     */
    fun diagnostics(buffers: Map<String, String>, filePath: String): List<Diagnostic>? =
        onCompilerThread { project ->
            buffers.forEach { (path, text) ->
                // `updateFile` marks the project dirty unconditionally, so hand it
                // only text the compiler has not already seen — otherwise every
                // query would rebuild.
                if (overlay.put(path, text) != text) project.updateFile(path, text)
            }
            // Only the file on screen is asked about: its answer walks just the slice
            // of the program it depends on, while a query over every open buffer would
            // recheck them all on each typing-driven pass. The config path rides along
            // because `diagnosticsOf` answers only about the files it is asked about,
            // and a config error — an unreadable or malformed `tsconfig.json` — is
            // reported against the config's own path; without it a broken config would
            // show a clean editor over a program checked with default options.
            project.diagnosticsOf(listOf(filePath, project.configPath))
        }

    /**
     * Closes the project on its own thread and returns without waiting for it; the
     * returned future completes once it has, or is `null` if the session was already closed.
     */
    fun close(): Future<*>? {
        // Losing this race means somebody else is closing: nothing left to do. Winning
        // it exactly once is what lets `close` be called from concurrent paths — the
        // service's dispose and its own disposed re-check — without either throwing.
        if (!closed.compareAndSet(false, true)) return null
        val closing = try {
            executor.submit {
                try {
                    project?.close()
                } catch (e: Exception) {
                    thisLogger().warn("Closing the xtsc project of $tsconfigPath failed", e)
                }
                project = null
                overlay.clear()
            }
        } catch (_: RejectedExecutionException) {
            // Only the winner of the race above reaches the `shutdown` below, so this
            // cannot happen — but an exception escaping here would abort the closing
            // of every other session in the service's dispose loop.
            return null
        }
        executor.shutdown()
        return closing
    }

    private fun <T> onCompilerThread(compute: (XtscProject) -> T): T? {
        if (closed.get()) return null
        val future: Future<T?> = try {
            // The submission races `close` when `closed` flips right after the check
            // above; re-checking on the session's own thread keeps a task queued in
            // that window from re-opening a project nothing would ever close again.
            executor.submit(Callable { if (closed.get()) null else compute(openProject()) })
        } catch (_: RejectedExecutionException) {
            return null
        }
        try {
            while (true) {
                // The compiler has no cancellation hook, so poll instead of blocking:
                // a pass the daemon abandons must not hold up the EDT's restart.
                ProgressManager.checkCanceled()
                try {
                    val result = future.get(POLL_MILLIS, TimeUnit.MILLISECONDS)
                    failureReported.set(false)
                    return result
                } catch (_: TimeoutException) {
                    continue
                }
            }
        } catch (e: ProcessCanceledException) {
            // A queued build that never started is dropped here, which is what keeps
            // a fast typist from accumulating a backlog of stale compilations.
            future.cancel(false)
            throw e
        } catch (e: ExecutionException) {
            // A failure that repeats on every pass — an unreadable `tsconfig.json`, a
            // compiler crash reproduced by the same source — would otherwise put a full
            // stack trace in the log on every restart of the daemon, every few hundred
            // milliseconds. One warning per streak of failures is all it needs.
            if (failureReported.compareAndSet(false, true)) {
                thisLogger().warn("xtsc failed on $tsconfigPath", e.cause ?: e)
            } else {
                thisLogger().debug("xtsc still failing on $tsconfigPath", e.cause ?: e)
            }
            return null
        } catch (e: InterruptedException) {
            future.cancel(false)
            Thread.currentThread().interrupt()
            return null
        }
    }

    /** Called on [executor]. `open` compiles nothing, so it is cheap to defer to here. */
    private fun openProject(): XtscProject =
        project ?: XtscProject.open(tsconfigPath).also { project = it }
}
