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

import com.intellij.openapi.components.service
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.xemantic.kotlin.test.assert
import com.xemantic.xtsc.intellij.ShadedDispatcherThreads
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * The service's own contracts, short of any compiler build.
 *
 * [ShadedDispatcherThreads] is registered although nothing here builds: the test
 * classes share one JVM, so a relocated dispatcher worker born from ANOTHER class's
 * teardown would otherwise fail this class's thread-leak check.
 */
@TestApplication
@ExtendWith(ShadedDispatcherThreads::class)
class XtscServiceTest {

    private val project = projectFixture()

    @Test
    fun `Should refuse a tsconfig path the compiler cannot resolve`() {
        // The compiler treats only `/`-rooted paths as absolute; a Windows drive-letter
        // path must be refused up front instead of failing on every highlighting pass.
        val service = project.get().service<XtscService>()
        assert(service.session("C:/project/tsconfig.json") == null)
    }
}
