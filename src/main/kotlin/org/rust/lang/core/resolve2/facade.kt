/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS
import com.intellij.openapiext.isUnitTestMode
import org.rust.ide.utils.isEnabledByCfg
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.crate.CratePersistentId
import org.rust.lang.core.crate.crateGraph
import org.rust.lang.core.crate.impl.DoctestCrate
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.*
import org.rust.lang.core.resolve.ItemProcessingMode.WITHOUT_PRIVATE_IMPORTS
import org.rust.lang.core.resolve2.Visibility.CfgDisabled
import org.rust.openapiext.fileId
import org.rust.openapiext.testAssert
import org.rust.openapiext.toPsiFile
import java.util.concurrent.Executor
import kotlin.system.measureTimeMillis

val IS_NEW_RESOLVE_ENABLED: Boolean = true
// val IS_NEW_RESOLVE_ENABLED: Boolean = isFeatureEnabled(RsExperiments.RESOLVE_NEW)

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

fun processItemDeclarations2(
    scope: RsMod,
    ns: Set<Namespace>,
    processor: RsResolveProcessor,
    ipm: ItemProcessingMode  // todo
): Boolean {
    val project = scope.project
    val defMap = getDefMap(scope) ?: return false
    // todo optimization: добавить в CrateDefMap мапку из fileId в ModData
    val modData = defMap.getModData(scope) ?: return false

    modData.visibleItems.processEntriesWithName(processor.name) { name, perNs ->
        fun /* todo inline */ VisItem.tryConvertToPsi(namespace: Namespace): RsNamedElement? {
            if (namespace !in ns) return null
            if (visibility.isInvisible && ipm === WITHOUT_PRIVATE_IMPORTS) return null

            val item = toPsi(defMap.defDatabase, project, namespace) ?: return null

            if ((visibility === CfgDisabled) != !item.isEnabledByCfg) return null

            val itemNamespaces = item.namespaces
            if (itemNamespaces == TYPES_N_VALUES) {
                // We will provide `item` only in [Namespace.Types]
                if (Namespace.Types in ns && namespace == Namespace.Values) return null
            } else {
                check(itemNamespaces.size == 1)
            }
            return item
        }

        // todo refactor ?
        // todo iterate over `ns` ?
        val types = perNs.types?.tryConvertToPsi(Namespace.Types)
        val values = perNs.values?.tryConvertToPsi(Namespace.Values)
        val macros = perNs.macros?.tryConvertToPsi(Namespace.Macros)
        // we need setOf here because item could belong to multiple namespaces (e.g. unit struct)
        for (element in setOf(types, values, macros)) {
            if (element == null) continue
            processor(name, element) && return@processEntriesWithName true
        }
        false
    } && return true

    // todo не обрабатывать отдельно, а использовать `getVisibleItems` ?
    // todo only if `processor.name == null` ?
    if (Namespace.Types in ns) {
        for ((traitPath, traitVisibility) in modData.unnamedTraitImports) {
            val trait = VisItem(traitPath, traitVisibility)
            val traitPsi = trait.toPsi(defMap.defDatabase, project, Namespace.Types) ?: continue
            processor("_", traitPsi) && return true
        }
    }

    if (ipm.withExternCrates && Namespace.Types in ns) {
        defMap.externPrelude.processEntriesWithName(processor.name) { name, externCrateModData ->
            if (modData.visibleItems[name]?.types != null) return@processEntriesWithName false
            val externCratePsi = externCrateModData.asVisItem().toPsi(defMap.defDatabase, project, Namespace.Types)!!  // todo
            processor(name, externCratePsi)
        } && return true
    }

    return false
}

fun processMacros(scope: RsMod, processor: RsResolveProcessor): Boolean {
    val project = scope.project
    val defMap = getDefMap(scope) ?: return false
    val modData = defMap.getModData(scope) ?: return false

    modData.legacyMacros.processEntriesWithName(processor.name) { name, macroInfo ->
        val visItem = VisItem(macroInfo.path, Visibility.Public)
        val macros = visItem.toPsi(defMap.defDatabase, project, Namespace.Macros)
            ?: return@processEntriesWithName false
        processor(name, macros)
    } && return true

    modData.visibleItems.processEntriesWithName(processor.name) { name, perNs ->
        val macros = perNs.macros?.toPsi(defMap.defDatabase, project, Namespace.Macros)
            ?: return@processEntriesWithName false
        processor(name, macros)
    } && return true
    return false
}

private fun getDefMap(scope: RsMod): CrateDefMap? {
    val crate = scope.containingCrate ?: return null
    check(crate !is DoctestCrate) { "doc test crates are not supported by CrateDefMap" }
    val defMap = crate.defMap
    if (defMap == null) {
        // todo
        if (isUnitTestMode) error("defMap is null for $crate during resolve")
        // println("defMap is null for $crate during resolve")
    }
    return defMap
}

// todo make inline? (станет удобнее делать `&& return true`)
private fun <T> Map<String, T>.processEntriesWithName(name: String?, f: (String, T) -> Boolean): Boolean {
    if (name == null) {
        for ((key, value) in this) {
            f(key, value) && return true
        }
        return false
    } else {
        val value = this[name] ?: return false
        return f(name, value)
    }
}

private fun VisItem.toPsi(defDatabase: DefDatabase, project: Project, ns: Namespace): RsNamedElement? {
    if (isModOrEnum) return path.toRsModOrEnum(defDatabase, project)
    val containingModOrEnum = containingMod.toRsModOrEnum(defDatabase, project) ?: return null
    return when (containingModOrEnum) {
        is RsMod -> {
            if (ns == Namespace.Macros) {
                // todo expandedItemsIncludingMacros
                val macros = containingModOrEnum.itemsAndMacros
                    .filterIsInstance<RsMacro>()
                    .filter { it.name == name }
                macros.lastOrNull { it.isEnabledByCfg } ?: macros.lastOrNull()
            } else {
                containingModOrEnum.expandedItemsExceptImplsAndUses
                    .filterIsInstance<RsNamedElement>()
                    .filter { it.name == name && ns in it.namespaces }
                    .singleOrCfgEnabled()
            }
        }
        is RsEnumItem -> containingModOrEnum.variants.find { it.name == name && ns in it.namespaces }
        else -> error("unreachable")
    }
}

// todo multiresolve
private inline fun <reified T : RsElement> Collection<T>.singleOrCfgEnabled(): T? =
    singleOrNull() ?: singleOrNull { it.isEnabledByCfg }

private fun ModPath.toRsModOrEnum(defDatabase: DefDatabase, project: Project): RsNamedElement? /* RsMod or RsEnumItem */ {
    val modData = defDatabase.getModData(this) ?: return null
    return if (modData.isEnum) {
        modData.toRsEnum(project)
    } else {
        modData.toRsMod(project)
    }
}

private fun ModData.toRsEnum(project: Project): RsEnumItem? {
    if (!isEnum) return null
    val containingMod = parent?.toRsMod(project) ?: return null
    return containingMod.expandedItemsExceptImplsAndUses
        .filter { it is RsEnumItem && it.name == path.name }
        .singleOrCfgEnabled()
        as RsEnumItem?
}

// todo assert not null / log warning
private fun ModData.toRsMod(project: Project, useExpandedItems /* todo remove (always true) */: Boolean = true): RsMod? {
    if (isEnum) return null
    val file = PersistentFS.getInstance().findFileById(fileId)
        ?.toPsiFile(project) as? RsFile
        ?: return null
    val fileRelativeSegments = fileRelativePath.split("::")
    return fileRelativeSegments
        .subList(1, fileRelativeSegments.size)
        .fold(file as RsMod) { mod, segment ->
            val items = if (useExpandedItems) mod.expandedItemsExceptImplsAndUses else mod.itemsAndMacros.toList()
            items
                .filterIsInstance<RsModItem>()
                .filter { it.modName == segment && it.isEnabledByCfg /* todo */ }
                .singleOrCfgEnabled()
                ?: return null
        }
}

// todo remove
val timesBuildDefMaps: MutableList<Long> = mutableListOf()
