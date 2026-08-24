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

package com.xemantic.xtsc.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.common.ThreadLeakTracker
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Excuses the idle workers of the compiler's `Dispatchers.Default` from the thread-leak
 * check that `@TestApplication` runs after every test.
 *
 * The platform already excuses its own, but by the exact class name of the parked thread:
 *
 * ```java
 * if (!"kotlinx.coroutines.scheduling.CoroutineScheduler$Worker".equals(thread.getClass().getName()))
 * ```
 *
 * The compiler's copy of kotlinx-coroutines is relocated into `…intellij.shaded.*`, so its
 * workers answer that question with a different name and the exemption never applies to
 * them. They are parked daemon threads of a global dispatcher that nothing can shut down,
 * so the only thing to do is name them; matching on the thread name is what
 * [ThreadLeakTracker.longRunningThreadCreated] is for.
 *
 * A worker is spawned when the scheduler decides it needs one, which is to say in whichever
 * test happens to ask the compiler something at the wrong moment — the failure this
 * prevents moves between tests from run to run.
 */
class ShadedDispatcherThreads : BeforeAllCallback, AfterAllCallback {

    private lateinit var disposable: Disposable

    @Suppress("UnstableApiUsage")
    override fun beforeAll(context: ExtensionContext) {
        disposable = Disposer.newDisposable("shaded coroutine dispatcher threads")
        ThreadLeakTracker.longRunningThreadCreated(disposable, "DefaultDispatcher-worker")
    }

    override fun afterAll(context: ExtensionContext) {
        Disposer.dispose(disposable)
    }
}
