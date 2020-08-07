/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapiext.isUnitTestMode
import org.rust.cargo.project.workspace.CargoWorkspace.Edition.EDITION_2015
import org.rust.cargo.util.AutoInjectedCrates.CORE
import org.rust.cargo.util.AutoInjectedCrates.STD
import org.rust.ide.utils.isEnabledByCfg
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.crate.CratePersistentId
import org.rust.lang.core.macros.MACRO_DOLLAR_CRATE_IDENTIFIER
import org.rust.lang.core.macros.RangeMap
import org.rust.lang.core.macros.RsExpandedElement
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.namespaces
import org.rust.openapiext.fileId
import org.rust.openapiext.testAssert
import kotlin.test.assertEquals

// todo придумать имя получше?
/** Contains static information of all explicitly declared imports and macros */
class CrateInfo {
    val imports: MutableList<Import> = mutableListOf()
    val macroCalls: MutableList<MacroCallInfo> = mutableListOf()
}

fun buildCrateDefMapContainingExplicitItems(
    crate: Crate,
    // have to pass `crate.id` and `crate.rootModule` as parameters,
    // because we want check them for null earlier
    crateId: CratePersistentId,
    crateRoot: RsFile
): Pair<CrateDefMap, CrateInfo> {
    val externPrelude = getInitialExternPrelude(crate)
    val directDependenciesDefMaps = crate.dependencies
        .mapNotNull {
            val defMap = it.crate.defMap ?: return@mapNotNull null
            it.normName to defMap
        }
        .toMap()
    // todo вынести в отдельный метод
    val allDependenciesDefMaps = crate.flatDependencies
        .mapNotNull {
            val id = it.id ?: return@mapNotNull null
            val defMap = it.defMap ?: return@mapNotNull null
            id to defMap
        }
        .toMap()
    // look for the prelude
    // If the dependency defines a prelude, we overwrite an already defined
    // prelude. This is necessary to import the "std" prelude if a crate
    // depends on both "core" and "std".
    // todo should find prelude in all dependencies or only direct ones ?
    // todo check that correct prelude is always selected (core vs std)
    val prelude: ModData? = allDependenciesDefMaps.values.map { it.prelude }.firstOrNull()

    val crateRootData = ModData(hashMapOf(), null, crateId, ModPath(crateId, emptyList()), crateRoot.virtualFile.fileId, "")
    val defMap = CrateDefMap(
        crateId,
        crate.edition,
        crateRootData,
        externPrelude,
        directDependenciesDefMaps,
        allDependenciesDefMaps,
        prelude,
        crate.toString()
    )

    val crateInfo = CrateInfo()
    val collector = ModCollector(crateRootData, defMap, crateRootData, crateInfo)
    createExternCrateStdImport(crateRoot, crateRootData)?.let {
        crateInfo.imports += it
        collector.importExternCrateMacros(it.usePath)
    }
    collector.collectElements(crateRoot)

    removeInvalidImportsAndMacroCalls(defMap, crateInfo)
    crateInfo.imports  // imports from nested modules first
        .sortByDescending { import -> import.usePath.split("::").size }
    return Pair(defMap, crateInfo)
}

private fun getInitialExternPrelude(crate: Crate): MutableMap<String, ModData> {
    return crate.dependencies
        .mapNotNull {
            val defMap = it.crate.defMap ?: return@mapNotNull null
            it.normName to defMap.root
        }
        .toMap(hashMapOf())
}

/**
 * "Invalid" means it belongs to [ModData] which is no longer accessible from [defMap.root] using [ModData.childModules]
 * It could happen if there is cfg-disabled module, which we collect first (with its imports)
 * And then cfg-enabled module overrides previously created [ModData]
 */
fun removeInvalidImportsAndMacroCalls(defMap: CrateDefMap, crateInfo: CrateInfo) {
    fun ModData.descendantsMods(): Sequence<ModData> =
        sequenceOf(this) + childModules.values.asSequence().flatMap { it.descendantsMods() }

    val allMods = defMap.root.descendantsMods().toSet()
    crateInfo.imports.removeIf { it.containingMod !in allMods }
    crateInfo.macroCalls.removeIf { it.containingMod !in allMods }
}

class ModCollector(
    private val modData: ModData,
    private val defMap: CrateDefMap,
    private val crateRoot: ModData,
    private val crateInfo: CrateInfo,
    private val macroDepth: Int = 0,
    /**
     * called when new [RsItemElement] is found
     * default behaviour: just add it to [ModData.visibleItems]
     * behaviour when processing expanded items:
     * add it to [ModData.visibleItems] and propagate to modules which have glob import from [ModData]
     */
    private val onAddItem: (ModData, String, PerNs) -> Unit =
        { containingMod, name, perNs -> containingMod.addVisibleItem(name, perNs) }
) {

    private val imports: MutableList<Import> get() = crateInfo.imports

    /** [itemsOwner] - [RsMod] or [RsForeignModItem] */
    fun collectElements(itemsOwner: RsItemsOwner) = collectElements(itemsOwner.itemsAndMacros.toList())

    fun collectExpandedItems(items: List<RsExpandedElement>) = collectElements(items)

    private fun collectElements(items: List<RsElement>) {
        // This should be processed eagerly instead of deferred to resolving.
        // `#[macro_use] extern crate` is hoisted to imports macros before collecting any other items.
        for (item in items) {
            if (item is RsExternCrateItem) {
                collectExternCrate(item)
            }
        }
        for (item in items) {
            if (item !is RsExternCrateItem) {
                collectElement(item)
            }
        }
        if (isUnitTestMode) {
            modData.checkChildModulesAndVisibleItemsConsistency()
        }
    }

    private fun collectElement(element: RsElement) {
        when (element) {
            // impls are not named elements, so we don't need them for name resolution
            is RsImplItem -> Unit

            is RsForeignModItem -> collectElements(element)

            is RsUseItem -> collectUseItem(element)
            is RsExternCrateItem -> error("extern crates are processed eagerly")

            is RsItemElement -> collectItem(element)

            is RsMacroCall -> collectMacroCall(element)
            is RsMacro -> collectMacro(element)

            // `RsOuterAttr`, `RsInnerAttr` or `RsVis` when `itemsOwner` is `RsModItem`
            // `RsExternAbi` when `itemsOwner` is `RsForeignModItem`
            // etc
            else -> Unit
        }
    }

    private fun collectUseItem(useItem: RsUseItem) {
        val visibility = useItem.getVisibility(modData, crateRoot)
        val hasPreludeImport = useItem.hasPreludeImport
        val dollarCrateId = useItem.getUserData(RESOLVE_DOLLAR_CRATE_ID_KEY)  // for `use $crate::`
        useItem.useSpeck?.forEachLeafSpeck { speck ->
            val import = convertToImport(speck, modData, visibility, hasPreludeImport, dollarCrateId)
            if (import != null) imports += import
        }
    }

    private fun collectExternCrate(externCrate: RsExternCrateItem) {
        if (externCrate.hasMacroUse && externCrate.isEnabledByCfg) {
            importExternCrateMacros(externCrate.referenceName)
        }
        imports += Import(
            modData,
            externCrate.referenceName,
            externCrate.nameWithAlias,
            externCrate.getVisibility(modData, crateRoot),
            isExternCrate = true,
            isMacroUse = externCrate.hasMacroUse
        )
    }

    // `#[macro_use] extern crate <name>;` - import macros
    fun importExternCrateMacros(externCrateName: String) {
        val externCrateDefMap = defMap.resolveExternCrateAsDefMap(externCrateName)
        if (externCrateDefMap != null) {
            defMap.importAllMacrosExported(externCrateDefMap)
        }
    }

    private fun collectItem(item: RsItemElement) {
        val name = item.name ?: return
        if (item !is RsNamedElement) return
        if (item is RsFunction && item.isProcMacroDef) return  // todo proc macros

        // could be null if `.resolve()` on `RsModDeclItem` returns null
        val childModData = tryCollectChildModule(item)

        val visItem = convertToVisItem(item, name, modData, crateRoot) ?: return
        val perNs = PerNs(visItem, item.namespaces)
        if (visItem.isModOrEnum && childModData == null) {
            perNs.types = null
            if (perNs.isEmpty) return
        }
        onAddItem(modData, name, perNs)

        // we have to check `modData[name]` to be sure that `childModules` and `visibleItems` are consistent
        if (childModData != null && perNs.types === modData[name].types) {
            modData.childModules[name] = childModData
        }
    }

    private fun tryCollectChildModule(item: RsNamedElement): ModData? {
        if (item is RsEnumItem) return collectEnumAsModData(item)

        val (childMod, hasMacroUse) = when (item) {
            is RsModItem -> item to item.hasMacroUse
            is RsModDeclItem -> {
                val childMod = item.reference.resolve() as? RsMod ?: return null
                childMod to item.hasMacroUse
            }
            else -> return null
        }
        val childModData = collectChildModule(childMod, item.name ?: return null)
        // Note: don't use `childMod.isEnabledByCfg`, because `isEnabledByCfg` doesn't work for `RsFile`
        if (hasMacroUse && item.isEnabledByCfg) modData.legacyMacros += childModData.legacyMacros
        return childModData
    }

    /**
     * We have to pass [childModName], because we can't use [RsMod.modName] -
     * if mod declaration is expanded from macro, then [RsFile.declaration] will be null
     */
    private fun collectChildModule(childMod: RsMod, childModName: String): ModData {
        val childModPath = modData.path.append(childModName)
        val (fileId, fileRelativePath) = if (childMod is RsFile) {
            childMod.virtualFile.fileId to ""
        } else {
            modData.fileId to "${modData.fileRelativePath}::$childModName"
        }
        val childModData = ModData(hashMapOf(), modData, modData.crate, childModPath, fileId, fileRelativePath)
        childModData.legacyMacros += modData.legacyMacros

        val collector = ModCollector(childModData, defMap, crateRoot, crateInfo)
        collector.collectElements(childMod)
        return childModData
    }

    private fun collectEnumAsModData(enum: RsEnumItem): ModData {
        val enumName = enum.name!!  // todo
        val enumPath = modData.path.append(enumName)
        val enumFileRelativePath = "${modData.fileRelativePath}::$enumName"
        val visibleItems = enum.variants
            .mapNotNull { variant ->
                val variantName = variant.name ?: return@mapNotNull null
                val variantPath = enumPath.append(variantName)
                val visItem = VisItem(variantPath, Visibility.Public)
                variantName to PerNs(visItem, variant.namespaces)
            }
            .toMap(hashMapOf())
        return ModData(
            visibleItems = visibleItems,
            parent = modData,
            crate = modData.crate,
            path = enumPath,
            fileId = modData.fileId,
            fileRelativePath = enumFileRelativePath,
            isEnum = true
        )
    }

    private fun collectMacroCall(call: RsMacroCall) {
        if (!call.isEnabledByCfg) return  // todo
        val body = call.includeMacroArgument?.expr?.value ?: call.macroBody ?: return
        val path = call.path.fullPath
        val dollarCrateId = call.path.getUserData(RESOLVE_DOLLAR_CRATE_ID_KEY)  // for `$crate::foo!()`
        val pathAdjusted = adjustPathWithDollacrCrate(path, dollarCrateId)
        val macroDef = if (path.contains("::")) null else modData.legacyMacros[path]
        val dollarCrateMap = call.getUserData(RESOLVE_RANGE_MAP_KEY) ?: RangeMap.EMPTY
        crateInfo.macroCalls += MacroCallInfo(modData, pathAdjusted, body, macroDepth, macroDef, dollarCrateMap)
    }

    private fun collectMacro(macro: RsMacro) {
        if (!macro.isEnabledByCfg) return  // todo ?
        val name = macro.name ?: return
        // check(macro.stub != null)  // todo
        val macroBodyStubbed = macro.macroBodyStubbed ?: return
        val macroPath = modData.path.append(name)

        modData.legacyMacros[name] = MacroInfo(modData.crate, macroPath, macroBodyStubbed)

        if (macro.hasMacroExport) {
            val visItem = VisItem(macroPath, Visibility.Public)
            val perNs = PerNs(macros = visItem)
            onAddItem(crateRoot, name, perNs)
        }
    }
}

private fun createExternCrateStdImport(crateRoot: RsFile, crateRootData: ModData): Import? {
    if (crateRoot.edition != EDITION_2015) return null
    // Rust injects implicit `extern crate std` in every crate root module unless it is
    // a `#![no_std]` crate, in which case `extern crate core` is injected. However, if
    // there is a (unstable?) `#![no_core]` attribute, nothing is injected.
    //
    // https://doc.rust-lang.org/book/using-rust-without-the-standard-library.html
    // The stdlib lib itself is `#![no_std]`, and the core is `#![no_core]`
    val name = when (crateRoot.attributes) {
        RsFile.Attributes.NONE -> STD
        RsFile.Attributes.NO_STD -> CORE
        RsFile.Attributes.NO_CORE -> return null
    }
    return Import(
        crateRootData,
        name,
        name,
        Visibility.Restricted(crateRootData),
        isExternCrate = true,
        isMacroUse = true
    )
}

private fun convertToVisItem(
    item: RsNamedElement,
    /** Passed for performance reason, because [RsFile.modName] is slow */
    name: String,
    containingMod: ModData,
    crateRoot: ModData
): VisItem? {
    val visibility = (item as? RsVisibilityOwner).getVisibility(containingMod, crateRoot)
    val itemPath = containingMod.path.append(name)
    val isModOrEnum = item is RsMod || item is RsModDeclItem || item is RsEnumItem
    return VisItem(itemPath, visibility, isModOrEnum)
}

private fun convertToImport(
    speck: RsUseSpeck,
    containingMod: ModData,
    visibility: Visibility,
    hasPreludeImport: Boolean,
    dollarCrateId: CratePersistentId?
): Import? {
    val isGlob = speck.isStarImport
    val (usePath, nameInScope) = if (isGlob) {
        val usePath = speck.getFullPath()
        val nameInScope = "_"  // todo
        usePath to nameInScope
    } else {
        testAssert { speck.useGroup == null }
        val path = speck.path
        val nameInScope = speck.nameInScope
        path?.fullPath to nameInScope
    }
    if (usePath == null || nameInScope == null) return null
    val usePathAdjusted = adjustPathWithDollacrCrate(usePath, dollarCrateId)
    return Import(containingMod, usePathAdjusted, nameInScope, visibility, isGlob, isPrelude = hasPreludeImport)
}

private fun RsUseSpeck.getFullPath(): String? {
    path?.let { return it.fullPath }
    return when (val parent = parent) {
        // `use ::*;`  (2015 edition)
        //        ^ speck
        is RsUseItem -> "crate"
        // `use aaa::{self, *};`
        //                  ^ speck
        // `use aaa::{{{*}}};`
        //              ^ speck
        is RsUseGroup -> (parent.parent as? RsUseSpeck)?.getFullPath()
        else -> null
    }
}

// before: `IntellijRustDollarCrate::foo;`
// after:  `IntellijRustDollarCrate::12345::foo;`
//                                   ~~~~~ crateId
private fun adjustPathWithDollacrCrate(path: String, dollarCrateId: CratePersistentId?): String {
    if (!path.startsWith(MACRO_DOLLAR_CRATE_IDENTIFIER)) return path

    check(dollarCrateId != null) { "Can't find crate for path starting with \$crate: '$path'" }
    return path.replaceFirst(MACRO_DOLLAR_CRATE_IDENTIFIER, "$MACRO_DOLLAR_CRATE_IDENTIFIER::$dollarCrateId")
}

private fun RsVisibilityOwner?.getVisibility(containingMod: ModData, crateRoot: ModData): Visibility {
    if (this == null) return Visibility.Public
    if (!isEnabledByCfg) return Visibility.CfgDisabled
    val vis = vis ?: return Visibility.Restricted(containingMod)
    return when (vis.stubKind) {
        RsVisStubKind.PUB -> Visibility.Public
        RsVisStubKind.CRATE -> Visibility.Restricted(crateRoot)
        RsVisStubKind.RESTRICTED -> {
            // https://doc.rust-lang.org/reference/visibility-and-privacy.html#pubin-path-pubcrate-pubsuper-and-pubself
            val path = vis.visRestriction!!.path
            val pathText = path.fullPath.removePrefix("::")  // 2015 edition, absolute paths
            if (pathText.isEmpty() || pathText == "crate") return Visibility.Restricted(crateRoot)

            val segments = pathText.split("::")
            val initialModData = when (segments.first()) {
                "super", "self" -> containingMod
                else -> crateRoot
            }
            val pathTarget = segments
                .fold(initialModData) { modData, segment ->
                    val nextModData = when (segment) {
                        "self" -> modData
                        "super" -> modData.parent
                        else -> modData.childModules[segment]
                    }
                    nextModData ?: return Visibility.Restricted(crateRoot)
                }
            Visibility.Restricted(pathTarget)
        }
    }
}

private fun ModData.checkChildModulesAndVisibleItemsConsistency() {
    for ((name, childMod) in childModules) {
        assertEquals(name, childMod.name, "Inconsistent name of $childMod")
        check(visibleItems[name]?.types?.isModOrEnum == true)
        { "Inconsistent `visibleItems` and `childModules` in $this for name $name" }
    }
}
