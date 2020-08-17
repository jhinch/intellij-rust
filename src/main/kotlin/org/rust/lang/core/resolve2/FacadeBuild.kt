/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.crate.CratePersistentId
import org.rust.lang.core.crate.crateGraph
import org.rust.lang.core.psi.rustPsiManager
import org.rust.openapiext.fileId
import org.rust.openapiext.testAssert
import java.util.concurrent.Executor
import kotlin.system.measureTimeMillis

fun updateDefMapForAllCrates(
    project: Project,
    pool: Executor,
    indicator: ProgressIndicator,
    isFirstTime: Boolean
) {
    if (isFirstTime) {
        buildDefMapForAllCrates(project, pool, indicator)
        return
    }

    val defMapService = project.defMapService
    val changedCrates = getChangedCrates(defMapService)
    defMapService.addChangedCrates(changedCrates)
    indicator.checkCanceled()

    // `changedCrates` will be processed in next task
    if (defMapService.hasChangedFiles()) return

    // todo async
    val changedCratesAll = defMapService.takeChangedCrates()
    val topSortedCrates = runReadAction { project.crateGraph.topSortedCrates }
        .filter {
            val id = it.id ?: return@filter false
            id in changedCratesAll
        }
    println("changedCrates: $topSortedCrates")
    for (crate in topSortedCrates) {
        crate.updateDefMap(indicator)
    }
}

private fun getChangedCrates(defMapService: DefMapService): Set<CratePersistentId> {
    val changedFiles = defMapService.takeChangedFiles()
    val changedCrates = hashSetOf<CratePersistentId>()
    for (file in changedFiles) {
        val (modificationStampPrev, crate) = defMapService.fileModificationStamps[file.virtualFile.fileId] ?: continue
        val modificationStampCurr = file.modificationStamp
        testAssert { modificationStampCurr >= modificationStampPrev }
        if (modificationStampCurr > modificationStampPrev) {
            changedCrates += crate
        }
    }
    return changedCrates
}

fun buildDefMapForAllCrates(
    project: Project,
    pool: Executor,
    indicator: ProgressIndicator,
    async: Boolean = true
) {
    indicator.checkCanceled()
    val crateGraph = project.crateGraph
    val topSortedCrates = runReadAction { crateGraph.topSortedCrates }
    if (topSortedCrates.isEmpty()) return

    println("\tbuildCrateDefMapForAllCrates")
    project.defMapService.defMaps.clear()
    val time = measureTimeMillis {
        if (async) {
            AsyncDefMapBuilder(pool, topSortedCrates, indicator).build()
        } else {
            for (crate in topSortedCrates) {
                crate.updateDefMap(indicator)
            }
        }
    }
    timesBuildDefMaps += time
    RESOLVE_LOG.info("Created DefMap for all crates in $time milliseconds")

    indicator.checkCanceled()
    project.rustPsiManager.incRustStructureModificationCount()
    DaemonCodeAnalyzer.getInstance(project).restart()
}

fun buildDefMap(crate: Crate, indicator: ProgressIndicator): CrateDefMap? {
    RESOLVE_LOG.info("Building DefMap for $crate")
    val project = crate.cargoProject.project
    val context = CollectorContext(crate, indicator)
    val defMap = runReadAction {
        buildDefMapContainingExplicitItems(context)
    } ?: return null
    DefCollector(project, defMap, context).collect()
    defMap.onBuildFinish()
    project.defMapService.fileModificationStamps += defMap.fileModificationStamps
        .mapValues { (_, time) -> time to defMap.crate }
    return defMap
}

// todo remove
val timesBuildDefMaps: MutableList<Long> = mutableListOf()
