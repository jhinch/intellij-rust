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
import org.rust.lang.core.psi.RsFile
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
    } else {
        buildDefMapForChangedCrates(project, indicator)
    }
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

    println("\tbuildDefMapForAllCrates")
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
    if (!async) println("wallTime: $time")

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
    calculateHashForAllFiles(defMap, context)
    project.defMapService.fileModificationStamps += defMap.fileInfos
        .mapValues { (_, info) -> info.modificationStamp to defMap.crate }
    return defMap
}

private fun buildDefMapForChangedCrates(project: Project, indicator: ProgressIndicator) {
    val defMapService = project.defMapService
    val changedCratesNew = getChangedCratesNew(defMapService, indicator)
    defMapService.addChangedCrates(changedCratesNew)
    indicator.checkCanceled()

    // `changedCrates` will be processed in next task
    if (defMapService.hasChangedFiles()) return

    val changedCrates = defMapService.takeChangedCrates()
    val changedCratesAll = topSortCratesAndAddReverseDependencies(changedCrates, project)
    println("\tbuildDefMapForChangedCrates: $changedCratesAll")
    // todo async
    for (crate in changedCratesAll) {
        crate.updateDefMap(indicator)
    }
}

private fun getChangedCratesNew(defMapService: DefMapService, indicator: ProgressIndicator): Set<CratePersistentId> {
    val changedFiles = defMapService.takeChangedFiles()
    val changedCratesCurr = defMapService.getChangedCrates()
    val changedCratesNew = hashSetOf<CratePersistentId>()
    for (file in changedFiles) {
        // todo зачем проверять modificationStamp?
        //  если файл был добавлен в changedFiles, то modificationStamp гарантированно изменился
        val (modificationStampPrev, crate) = defMapService.fileModificationStamps[file.virtualFile.fileId]
        // todo может быть такое, что defMap ещё не была посчитана самый первый раз ?
            ?: continue
        val modificationStampCurr = file.viewProvider.modificationStamp
        testAssert { modificationStampCurr >= modificationStampPrev }
        if (modificationStampCurr == modificationStampPrev) continue

        // can skip hash comparison if we already scheduled building [CrateDefMap] for [crate]
        if (crate in changedCratesNew || crate in changedCratesCurr) continue

        // todo
        runReadAction {
            // todo
            val defMap = file.project.crateGraph.findCrateById(crate)?.defMap ?: return@runReadAction
            if (isFileChanged(file, defMap, indicator)) {
                changedCratesNew += crate
            }
        }
    }
    return changedCratesNew
}

private fun isFileChanged(file: RsFile, defMap: CrateDefMap, indicator: ProgressIndicator): Boolean {
    // todo return это ок ?
    val fileInfo = defMap.fileInfos[file.virtualFile.fileId] ?: return false
    val crateId = defMap.crate
    // todo ?
    val crate = runReadAction { file.project.crateGraph.findCrateById(crateId) } ?: return false

    // todo indicator
    val context = CollectorContext(crate, indicator)
    val (fileModDataFake, crateRootDataFake, defMapFake) = prepareFakeModData(defMap, fileInfo.modData, crateId)
    ModCollector(fileModDataFake, defMapFake, crateRootDataFake, context, isUsualCollect = false).collectMod(file)

    val hash = calculateFileHash(fileModDataFake, context)
    return hash != fileInfo.hash
}

// todo найти способ получше?  хотя бы без defMapFake
private fun prepareFakeModData(
    defMap: CrateDefMap,
    fileModData: ModData,
    crateId: CratePersistentId
): Triple<ModData, ModData, CrateDefMap> {
    val crateRootDataFake = ModData(
        parent = null,
        crate = crateId,
        path = ModPath(crateId, emptyList()),
        isEnabledByCfg = true,
        fileId = defMap.root.fileId,
        fileRelativePath = "",
        ownedDirectoryId = defMap.root.ownedDirectoryId
    )
    val defMapFake = CrateDefMap(
        crate = crateId,
        edition = defMap.edition,
        root = crateRootDataFake,
        // dependencies and prelude should not be used in [ModCollector]
        externPrelude = hashMapOf(),
        directDependenciesDefMaps = hashMapOf(),
        allDependenciesDefMaps = hashMapOf(),
        prelude = null,
        crateDescription = defMap.crateDescription
    )
    val fileModDataFake = fileModData.parents.toList().asReversed()
        .fold(crateRootDataFake) { parentFake, modData ->
            ModData(
                parent = parentFake,
                crate = crateId,
                path = modData.path,
                isEnabledByCfg = true,
                fileId = modData.fileId,
                fileRelativePath = modData.fileRelativePath,
                ownedDirectoryId = modData.ownedDirectoryId
            )
        }
    return Triple(fileModDataFake, crateRootDataFake, defMapFake)
}

private fun topSortCratesAndAddReverseDependencies(crateIds: Set<CratePersistentId>, project: Project): List<Crate> {
    // todo
    return runReadAction {
        val topSortedCrates = project.crateGraph.topSortedCrates
        val crates = topSortedCrates.filter {
            val id = it.id ?: return@filter false
            id in crateIds
        }
        val cratesAll = crates.withReversedDependencies()
        topSortedCrates.filter { it in cratesAll }
    }
}

private fun List<Crate>.withReversedDependencies(): Set<Crate> {
    val result = hashSetOf<Crate>()
    fun processCrate(crate: Crate) {
        if (crate in result) return
        result += crate
        for (reverseDependency in crate.reverseDependencies) {
            processCrate(reverseDependency)
        }
    }
    for (crate in this) {
        processCrate(crate)
    }
    return result
}

// todo remove
val timesBuildDefMaps: MutableList<Long> = mutableListOf()
