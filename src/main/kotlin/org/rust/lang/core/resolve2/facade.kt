/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS
import org.rust.ide.utils.isEnabledByCfg
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.crate.crateGraph
import org.rust.lang.core.crate.impl.DoctestCrate
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.*
import org.rust.lang.core.resolve.ItemProcessingMode.WITHOUT_PRIVATE_IMPORTS
import org.rust.lang.core.resolve2.Visibility.CfgDisabled
import org.rust.openapiext.toPsiFile
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kotlin.system.measureTimeMillis

fun buildCrateDefMapForAllCrates(project: Project, pool: Executor, async: Boolean = true) {
    val topSortedCrates = runReadAction { project.crateGraph.topSortedCrates }
    if (topSortedCrates.isEmpty()) return

    // println("\trunNameResolution")
    for (crate in topSortedCrates) {
        crate.resetDefMap()
    }
    val time = measureTimeMillis {
        if (async) {
            AsyncCrateDefMapBuilder(pool, topSortedCrates).build()
        } else {
            for (crate in topSortedCrates) {
                crate.updateDefMap()
            }
        }
    }
    timesBuildDefMaps += time
    RESOLVE_LOG.info("Created DefMap for all crates in $time milliseconds")

    project.rustPsiManager.incRustStructureModificationCount()
    DaemonCodeAnalyzer.getInstance(project).restart()
}

private class AsyncCrateDefMapBuilder(
    private val pool: Executor,
    topSortedCrates: List<Crate>
) {
    /** Values - number of dependencies for which [CrateDefMap] is not build yet */
    private val remainingDependenciesCounts: MutableMap<Crate, Int> = topSortedCrates
        .associateWith { it.dependencies.size }
        .toMutableMap()
    private val completableFuture: CompletableFuture<Unit> = CompletableFuture()

    // for profiling
    private val tasksTimes: MutableMap<Crate, Long> = ConcurrentHashMap()

    @Volatile
    private var remainingNumberCrates: Int = topSortedCrates.size

    fun build() {
        val wallTime = measureTimeMillis {
            buildImpl()
        }

        val totalTime = tasksTimes.values.sum()
        println("wallTime: $wallTime, totalTime: $totalTime, " +
            "parallelism coefficient: ${"%.2f".format((totalTime.toDouble() / wallTime))}")
        val top5crates = tasksTimes.entries
            .sortedByDescending { (_, time) -> time }
            .take(5)
            .joinToString { (crate, time) -> "$crate ${time}ms" }
        println("Top 5 crates: $top5crates")
    }

    fun buildImpl() {
        remainingDependenciesCounts
            .filterValues { it == 0 }
            .keys
            .forEach { buildCrateDefMapAsync(it) }
        completableFuture.join()
    }

    private fun buildCrateDefMapAsync(crate: Crate) {
        pool.execute {
            try {
                tasksTimes[crate] = measureTimeMillis {
                    crate.updateDefMap()
                }
            } catch (e: Exception) {
                try {
                    RESOLVE_LOG.error(e)
                } catch (e: AssertionError) {
                    // ignored
                }
            }
            onCrateFinished(crate)
        }
    }

    @Synchronized
    private fun onCrateFinished(crate: Crate) {
        crate.reverseDependencies.forEach { onDependencyCrateFinished(it) }
        remainingNumberCrates -= 1
        if (remainingNumberCrates == 0) {
            completableFuture.complete(Unit)
        }
    }

    private fun onDependencyCrateFinished(crate: Crate) {
        var count = remainingDependenciesCounts.getValue(crate)
        count -= 1
        remainingDependenciesCounts[crate] = count
        if (count == 0) {
            buildCrateDefMapAsync(crate)
        }
    }
}

fun buildCrateDefMap(crate: Crate): CrateDefMap? {
    // println("Building DefMap for $crate")
    var defMap: CrateDefMap? = null
    val time = measureTimeMillis { defMap = buildCrateDefMapImpl(crate) }
    // println("Building DefMap for $crate - finished in $time milliseconds")
    return defMap
}

private fun buildCrateDefMapImpl(crate: Crate): CrateDefMap? {
    RESOLVE_LOG.info("Building DefMap for $crate")
    val project = crate.cargoProject.project
    val (defMap, crateInfo) = runReadAction {
        // todo inline into buildCrateDefMapContainingExplicitItems ?
        val crateId = crate.id ?: return@runReadAction null
        val crateRoot = crate.rootMod ?: return@runReadAction null
        buildCrateDefMapContainingExplicitItems(crate, crateId, crateRoot)
    } ?: run {
        RESOLVE_LOG.info("null DefMap for crate $crate")
        return null
    }
    DefCollector(project, defMap, crateInfo).collect()
    return defMap
}

fun processItemDeclarations2(
    scope: RsMod,
    ns: Set<Namespace>,
    processor: RsResolveProcessor,
    ipm: ItemProcessingMode  // todo
): Boolean {
    val project = scope.project
    val crate = scope.containingCrate ?: return false
    check(crate !is DoctestCrate) { "doc test crates are not supported by CrateDefMap" }
    val defMap = crate.defMap ?: error("defMap is null for $crate during resolve")
    val modData = defMap.getModData(scope) ?: return false

    // todo optimization: попробовать избавиться от цикла и передавать name как параметр
    val namesInTypesNamespace = hashSetOf<String>()
    for ((name, perNs) in modData.visibleItems) {
        /* todo inline */ fun VisItem.tryConvertToPsi(namespace: Namespace): RsNamedElement? {
            if (namespace !in ns) return null
            if (visibility.isInvisible && ipm === WITHOUT_PRIVATE_IMPORTS) return null
            return toPsi(defMap.defDatabase, project, namespace)
                ?.takeIf { it.isEnabledByCfg || visibility === CfgDisabled }
        }

        // todo refactor ?
        // todo iterate over `ns` ?
        val types = perNs.types?.tryConvertToPsi(Namespace.Types)
        val values = perNs.values?.tryConvertToPsi(Namespace.Values)
        val macros = perNs.macros?.tryConvertToPsi(Namespace.Macros)
        // we need setOf here because item could belong to multiple namespaces (e.g. unit struct)
        for (element in setOf(types, values, macros)) {
            if (element == null) continue
            val entry = SimpleScopeEntry(name, element)
            processor(entry) && return true
        }

        if (types != null) namesInTypesNamespace += name
    }

    // todo не обрабатывать отдельно, а использовать `getVisibleItems` ?
    if (Namespace.Types in ns) {
        for ((traitPath, traitVisibility) in modData.unnamedTraitImports) {
            val trait = VisItem(traitPath, traitVisibility)
            val traitPsi = trait.toPsi(defMap.defDatabase, project, Namespace.Types) ?: continue
            val entry = SimpleScopeEntry("_", traitPsi)
            processor(entry) && return true
        }
    }

    if (ipm.withExternCrates && Namespace.Types in ns) {
        for ((name, externCrateModData) in defMap.externPrelude) {
            if (name in namesInTypesNamespace) continue
            val externCratePsi = externCrateModData.asVisItem().toPsi(defMap.defDatabase, project, Namespace.Types)!!  // todo
            val entry = SimpleScopeEntry(name, externCratePsi)
            processor(entry) && return true
        }
    }

    return false
}

fun processMacros(scope: RsMod, processor: (ScopeEntry) -> Boolean): Boolean {
    val project = scope.project
    val crate = scope.containingCrate ?: return false
    val defMap = crate.defMap ?: error("defMap is null for $crate during macro resolve")
    val modData = defMap.getModData(scope) ?: return false

    for ((name, macroInfo) in modData.legacyMacros) {
        val visItem = VisItem(macroInfo.path, Visibility.Public)
        val macros = visItem.toPsi(defMap.defDatabase, project, Namespace.Macros) ?: continue
        val entry = SimpleScopeEntry(name, macros)
        processor(entry) && return true
    }

    for ((name, perNs) in modData.visibleItems) {
        val macros = perNs.macros?.toPsi(defMap.defDatabase, project, Namespace.Macros) ?: continue
        val entry = SimpleScopeEntry(name, macros)
        processor(entry) && return true
    }
    return false
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

fun ModPath.toRsModOrEnum(defDatabase: DefDatabase, project: Project): RsNamedElement? /* RsMod or RsEnumItem */ {
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
fun ModData.toRsMod(project: Project, useExpandedItems: Boolean = true): RsMod? {
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
