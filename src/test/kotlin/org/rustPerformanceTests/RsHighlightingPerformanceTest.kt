/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rustPerformanceTests

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.util.PsiModificationTracker
import org.rust.lang.core.macros.MacroExpansionScope
import org.rust.lang.core.macros.macroExpansionManager
import org.rust.lang.core.psi.ext.RsReferenceElement
import org.rust.lang.core.psi.ext.descendantsOfType
import org.rust.lang.core.psi.rustPsiManager
import org.rust.lang.core.resolve2.IS_NEW_RESOLVE_ENABLED
import org.rust.lang.core.resolve2.buildCrateDefMapForAllCrates
import org.rust.lang.core.resolve2.timesBuildDefMaps
import org.rust.stdext.Timings
import org.rust.stdext.repeatBenchmark
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.system.measureTimeMillis

// todo другое имя ?
class RsHighlightingPerformanceTest : RsRealProjectTestBase() {
    // It is a performance test, but we don't want to waste time
    // measuring CPU performance
    override fun isPerformanceTest(): Boolean = false

    private val cargoFilePath: String = "src/cargo/core/resolver/mod.rs"
    fun `test Cargo, measure`() = repeatTest(CARGO, cargoFilePath, ::measureResolveAndHighlight)
    fun `test Cargo, profile DefMap`() = repeatTest(CARGO, cargoFilePath, ::profileBuildDefMaps)
    fun `test Cargo, profile resolve`() = repeatTest(CARGO, cargoFilePath, ::profileResolve)

    private val mysqlAsyncFilePath1: String = "src/conn/mod.rs"
    fun `test mysql_async, measure 1`() = repeatTest(MYSQL_ASYNC, mysqlAsyncFilePath1, ::measureResolveAndHighlight)
    fun `test mysql_async, profile resolve 1`() = repeatTest(MYSQL_ASYNC, mysqlAsyncFilePath1, ::profileResolve)

    private val mysqlAsyncFilePath2: String = "src/connection_like/mod.rs"
    fun `test mysql_async, measure 2`() = repeatTest(MYSQL_ASYNC, mysqlAsyncFilePath2, ::measureResolveAndHighlight)
    fun `test mysql_async, profile resolve 2`() = repeatTest(MYSQL_ASYNC, mysqlAsyncFilePath2, ::profileResolve)

    private fun repeatTest(info: RealProjectInfo, filePath: String, f: (Timings) -> Unit) {
        println("${name.substring("test ".length)}:")
        repeatBenchmark {
            val disposable = project.macroExpansionManager.setUnitTestExpansionModeAndDirectory(MacroExpansionScope.ALL, name)
            openRealProject(info) ?: return@repeatBenchmark
            myFixture.configureFromTempProjectFile(filePath)
            f(it)
            Disposer.dispose(disposable)
            super.tearDown()
            super.setUp()
        }
    }

    private fun measureResolveAndHighlight(timings: Timings) {
        val modificationCount = currentPsiModificationCount()
        buildDefMaps(timings)
        val references = collectReferences(timings)
        resolveReferences(timings, references)
        highlight(timings)
        resolveReferencesCached(references, modificationCount, timings)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun profileBuildDefMaps(timings: Timings) {
        for (i in 0..Int.MAX_VALUE) {
            val pool = Executors.newWorkStealingPool()
            buildCrateDefMapForAllCrates(project, pool, async = true)
        }
    }

    private fun profileResolve(timings: Timings) {
        val references = collectReferences(timings)
        var timeMin = Long.MAX_VALUE
        for (i in 0..Int.MAX_VALUE) {
            val time = measureTimeMillis {
                references.forEach { it.reference?.resolve() }
            }
            timeMin = min(timeMin, time)
            println("Resolved all file references in $time ms  (min time is $timeMin ms)")
            project.rustPsiManager.incRustStructureModificationCount()
        }
    }

    private fun buildDefMaps(timings: Timings) {
        if (!IS_NEW_RESOLVE_ENABLED) return

        // Actually [CrateDefMap]s are built two times,
        // in [openRealProject] and [CodeInsightTestFixture.configureFromTempProjectFile]
        val time = timesBuildDefMaps.last()
        timesBuildDefMaps.clear()
        timings.addMeasure("resolve2", time)
    }

    private fun collectReferences(timings: Timings): Collection<RsReferenceElement> =
        timings.measure("collecting") {
            myFixture.file.descendantsOfType()
        }

    private fun resolveReferences(timings: Timings, references: Collection<RsReferenceElement>) =
        timings.measure("resolve") {
            references.forEach { it.reference?.resolve() }
        }

    private fun highlight(timings: Timings) =
        timings.measure("highlighting") {
            myFixture.doHighlighting()
        }

    private fun resolveReferencesCached(
        references: Collection<RsReferenceElement>,
        oldModificationCount: Long,
        timings: Timings
    ) {
        check(oldModificationCount == currentPsiModificationCount()) {
            "PSI changed during resolve and highlighting, resolve might be double counted"
        }
        timings.measure("resolve_cached") {
            references.forEach { it.reference?.resolve() }
        }
    }

    private fun currentPsiModificationCount() =
        PsiModificationTracker.SERVICE.getInstance(project).modificationCount

    override val disableMissedCacheAssertions: Boolean get() = false
    private val lastDisposable = Disposer.newDisposable("RsHighlightingPerformanceTest last")

    override fun setUp() {
        super.setUp()
        RecursionManager.disableMissedCacheAssertions(lastDisposable)
    }

    override fun tearDown() {
        super.tearDown()
        Disposer.dispose(lastDisposable)
    }
}
