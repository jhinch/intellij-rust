/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rustPerformanceTests

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.util.PsiModificationTracker
import org.rust.ide.experiments.RsExperiments
import org.rust.lang.core.crate.crateGraph
import org.rust.lang.core.macros.MacroExpansionScope
import org.rust.lang.core.macros.macroExpansionManager
import org.rust.lang.core.macros.timesBuildDefMaps
import org.rust.lang.core.psi.ext.RsReferenceElement
import org.rust.lang.core.psi.ext.descendantsOfType
import org.rust.openapiext.isFeatureEnabled
import org.rust.stdext.Timings
import org.rust.stdext.repeatBenchmark
import kotlin.system.measureTimeMillis


class RsHighlightingPerformanceTest : RsRealProjectTestBase() {
    // It is a performance test, but we don't want to waste time
    // measuring CPU performance
    override fun isPerformanceTest(): Boolean = false

    fun `test highlighting Cargo`() =
        repeatTest { highlightProjectFile(CARGO, "src/cargo/core/resolver/mod.rs", it) }

    fun `test highlighting mysql_async`() =
        repeatTest { highlightProjectFile(MYSQL_ASYNC, "src/conn/mod.rs", it) }

    fun `test highlighting mysql_async 2`() =
        repeatTest { highlightProjectFile(MYSQL_ASYNC, "src/connection_like/mod.rs", it) }

    private fun repeatTest(f: (Timings) -> Unit) {
        println("${name.substring("test ".length)}:")
        repeatBenchmark {
            val disposable = project.macroExpansionManager.setUnitTestExpansionModeAndDirectory(MacroExpansionScope.ALL, name)
            f(it)
            Disposer.dispose(disposable)
            super.tearDown()
            super.setUp()
        }
    }

    private fun highlightProjectFile(info: RealProjectInfo, filePath: String, timings: Timings): Timings {
        openRealProject(info) ?: return timings

        myFixture.configureFromTempProjectFile(filePath)

        val modificationCount = currentPsiModificationCount()

        // otherwise only profile build
        val measureBuildAndResolve = false
        if (isFeatureEnabled(RsExperiments.RESOLVE_NEW)) {
            if (measureBuildAndResolve) {
                val time = timesBuildDefMaps.last()
                timesBuildDefMaps.clear()
                timings.addMeasure("resolve2", time)
            } else {
                for (i in 0..Int.MAX_VALUE) {
                    val time = measureTimeMillis {
                        for (crate in project.crateGraph.topSortedCrates) {
                            crate.updateDefMap()
                            check(crate.defMap != null)
                        }
                    }
                    println("Iteration #$i - $time milliseconds")

                    // myFixture.editor.caretModel.moveToOffset(myFixture.file.endOffset)
                    // myFixture.type("pub fn foo$i() {}")
                    // PsiDocumentManager.getInstance(project).commitAllDocuments() // process PSI modification events
                }
            }
        }

        val refs = timings.measure("collecting") {
            myFixture.file.descendantsOfType<RsReferenceElement>()
        }

        timings.measure("resolve") {
            refs.forEach { it.reference?.resolve() }
        }
        timings.measure("highlighting") {
            myFixture.doHighlighting()
        }

        check(modificationCount == currentPsiModificationCount()) {
            "PSI changed during resolve and highlighting, resolve might be double counted"
        }

        timings.measure("resolve_cached") {
            refs.forEach { it.reference?.resolve() }
        }

        // myFixture.file.descendantsOfType<RsFunction>()
        //     .asSequence()
        //     .mapNotNull { it.block?.stmtList?.lastOrNull() }
        //     .forEach { stmt ->
        //         myFixture.editor.caretModel.moveToOffset(stmt.textOffset)
        //         myFixture.type("2+2;")
        //         PsiDocumentManager.getInstance(project).commitAllDocuments() // process PSI modification events
        //
        //         timings.measureAverage("resolve_after_typing") {
        //             refs.forEach { it.reference?.resolve() }
        //         }
        //     }
        //
        // myFixture.file.descendantsOfType<RsFunction>()
        //     .asSequence()
        //     .mapNotNull { it.block?.stmtList?.lastOrNull() }
        //     .forEach { stmt ->
        //         myFixture.editor.caretModel.moveToOffset(stmt.textOffset)
        //         // replace to `myFixture.type("Hash;")` to make it 10x slower
        //         myFixture.type("HashMa;")
        //         myFixture.editor.caretModel.moveCaretRelatively(-1, 0, false, false, false)
        //         timings.measureAverage("completion") {
        //             myFixture.completeBasic()
        //         }
        //     }

        return timings
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
