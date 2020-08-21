/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS
import com.intellij.openapiext.isUnitTestMode
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import org.rust.cargo.project.workspace.CargoWorkspace.Edition.EDITION_2015
import org.rust.cargo.util.AutoInjectedCrates.CORE
import org.rust.cargo.util.AutoInjectedCrates.STD
import org.rust.lang.RsConstants
import org.rust.lang.RsFileType
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.crate.CratePersistentId
import org.rust.lang.core.macros.MACRO_DOLLAR_CRATE_IDENTIFIER
import org.rust.lang.core.macros.RangeMap
import org.rust.lang.core.macros.RsExpandedElement
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.RsModDeclItemData
import org.rust.lang.core.resolve.collectResolveVariants
import org.rust.lang.core.resolve.namespaces
import org.rust.lang.core.resolve.processModDeclResolveVariants
import org.rust.openapiext.*
import kotlin.test.assertEquals

// todo move to facade ?
class CollectorContext(
    val crate: Crate,
    val indicator: ProgressIndicator
) {
    val project: Project = crate.cargoProject.project

    /** All explicit imports (not expanded from macros) */
    val imports: MutableList<Import> = mutableListOf()

    /** All explicit macro calls  */
    val macroCalls: MutableList<MacroCallInfo> = mutableListOf()
}

fun buildDefMapContainingExplicitItems(context: CollectorContext): CrateDefMap? {
    val crate = context.crate
    val crateId = crate.id ?: return null
    val crateRoot = crate.rootMod ?: return null

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

    val crateRootOwnedDirectory = crateRoot.parent
        ?: error("Can't find parent directory for crate root of $crate crate")
    val crateRootData = ModData(
        parent = null,
        crate = crateId,
        path = ModPath(crateId, emptyList()),
        isEnabledByCfg = true,
        fileId = crateRoot.virtualFile.fileId,
        fileRelativePath = "",
        ownedDirectoryId = crateRootOwnedDirectory.virtualFile.fileId
    )
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

    val collector = ModCollector(crateRootData, defMap, crateRootData, context)
    createExternCrateStdImport(crateRoot, crateRootData)?.let {
        context.imports += it
        collector.importExternCrateMacros(it.usePath)
    }
    collector.collectMod(crateRoot)

    removeInvalidImportsAndMacroCalls(defMap, context)
    context.imports  // imports from nested modules first
        .sortByDescending { import -> import.usePath.split("::").size }
    return defMap
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
 * "Invalid" means it belongs to [ModData] which is no longer accessible from `defMap.root` using [ModData.childModules]
 * It could happen if there is cfg-disabled module, which we collect first (with its imports)
 * And then cfg-enabled module overrides previously created [ModData]
 */
fun removeInvalidImportsAndMacroCalls(defMap: CrateDefMap, context: CollectorContext) {
    fun ModData.descendantsMods(): Sequence<ModData> =
        sequenceOf(this) + childModules.values.asSequence().flatMap { it.descendantsMods() }

    val allMods = defMap.root.descendantsMods().toSet()
    context.imports.removeIf { it.containingMod !in allMods }
    context.macroCalls.removeIf { it.containingMod !in allMods }
}

class ModCollector(
    private val modData: ModData,
    private val defMap: CrateDefMap,
    private val crateRoot: ModData,
    private val context: CollectorContext,
    private val macroDepth: Int = 0,
    /**
     * `true` when collecting during building DefMap.
     * `false` when collecting during calculating file hash
     */
    // todo refactor (make abstract class?)
    private val isUsualCollect: Boolean = true,
    /**
     * called when new [RsItemElement] is found
     * default behaviour: just add it to [ModData.visibleItems]
     * behaviour when processing expanded items:
     * add it to [ModData.visibleItems] and propagate to modules which have glob import from [ModData]
     */
    private val onAddItem: (ModData, String, PerNs) -> Unit =
        { containingMod, name, perNs -> containingMod.addVisibleItem(name, perNs) }
) {

    private val imports: MutableList<Import> get() = context.imports
    private val crate: Crate get() = context.crate
    private val project: Project get() = context.project

    fun collectMod(mod: RsMod) {
        if (isUsualCollect && mod is RsFile) defMap.addVisitedFile(mod, modData)
        collectElements(mod)
    }

    fun collectExpandedItems(items: List<RsExpandedElement>) = collectElements(items)

    /** [itemsOwner] - [RsMod] or [RsForeignModItem] */
    private fun collectElements(itemsOwner: RsItemsOwner) = collectElements(itemsOwner.itemsAndMacros.toList())

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
        val isEnabledByCfg = modData.isEnabledByCfg && useItem.isEnabledByCfgSelf(crate)
        val visibility = useItem.getVisibility(modData, crateRoot, isEnabledByCfg)
        val hasPreludeImport = useItem.hasPreludeImport
        val dollarCrateId = useItem.getUserData(RESOLVE_DOLLAR_CRATE_ID_KEY)  // for `use $crate::`
        useItem.useSpeck?.forEachLeafSpeck { speck ->
            val import = convertToImport(speck, modData, visibility, hasPreludeImport, dollarCrateId)
            if (import != null) imports += import
        }
    }

    private fun collectExternCrate(externCrate: RsExternCrateItem) {
        val isEnabledByCfg = modData.isEnabledByCfg && externCrate.isEnabledByCfgSelf(crate)
        onCollectExternCrate(externCrate, isEnabledByCfg)
        imports += Import(
            modData,
            externCrate.referenceName,
            externCrate.nameWithAlias,
            externCrate.getVisibility(modData, crateRoot, isEnabledByCfg),
            isExternCrate = true,
            isMacroUse = externCrate.hasMacroUse
        )
    }

    private fun onCollectExternCrate(externCrate: RsExternCrateItem, isEnabledByCfg: Boolean) {
        if (isUsualCollect && isEnabledByCfg && externCrate.hasMacroUse) {
            importExternCrateMacros(externCrate.referenceName)
        }
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

        val visItem = convertToVisItem(item, name) ?: return
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

    // todo причём здесь RsFile ?
    /** [name] passed for performance reason, because [RsFile.modName] is slow */
    private fun convertToVisItem(item: RsItemElement, name: String): VisItem? {
        val isEnabledByCfg = modData.isEnabledByCfg && item.isEnabledByCfgSelf(crate)
        val visibility = (item as? RsVisibilityOwner).getVisibility(modData, crateRoot, isEnabledByCfg)
        val itemPath = modData.path.append(name)
        val isModOrEnum = item is RsMod || item is RsModDeclItem || item is RsEnumItem
        return VisItem(itemPath, visibility, isModOrEnum)
    }

    private fun tryCollectChildModule(item: RsItemElement): ModData? {
        if (item is RsEnumItem) return collectEnumAsModData(item)

        val (childMod, hasMacroUse, pathAttribute) = when (item) {
            is RsModItem -> Triple(item, item.hasMacroUse, item.pathAttribute)
            is RsModDeclItem -> {
                val childMod: RsMod = if (isUsualCollect) {
                    item.resolve(modData, project) ?: return null
                } else {
                    // We can't just return `null` because then `item` will not be added to `visibleItems`.
                    // todo: use singleton ?
                    RsPsiFactory(project).createModItem("__tmp__", "")
                }
                Triple(childMod, item.hasMacroUse, item.pathAttribute)
            }
            else -> return null
        }
        // Note: don't use `childMod.isEnabledByCfgSelf`, because `isEnabledByCfg` doesn't work for `RsFile`
        val childModName = item.name ?: return null
        val isEnabledByCfg = modData.isEnabledByCfg && item.isEnabledByCfgSelf(crate)
        val childModData = collectChildModule(childMod, childModName, isEnabledByCfg, pathAttribute)
        if (hasMacroUse && isEnabledByCfg) modData.legacyMacros += childModData.legacyMacros
        return childModData
    }

    /**
     * We have to pass [childModName], because we can't use [RsMod.modName] -
     * if mod declaration is expanded from macro, then [RsFile.declaration] will be null
     */
    private fun collectChildModule(
        childMod: RsMod,
        childModName: String,
        isEnabledByCfg: Boolean,
        pathAttribute: String?
    ): ModData {
        context.indicator.checkCanceled()
        val childModPath = modData.path.append(childModName)
        val (fileId, fileRelativePath) = if (childMod is RsFile) {
            childMod.virtualFile.fileId to ""
        } else {
            modData.fileId to "${modData.fileRelativePath}::$childModName"
        }
        val childModData = ModData(
            parent = modData,
            crate = modData.crate,
            path = childModPath,
            isEnabledByCfg = isEnabledByCfg,
            fileId = fileId,
            fileRelativePath = fileRelativePath,
            ownedDirectoryId = childMod.getOwnedDirectory(modData, pathAttribute)?.virtualFile?.fileId
        )
        // todo не делать если вызывается из expandMacros ?
        childModData.legacyMacros += modData.legacyMacros

        val collector = ModCollector(childModData, defMap, crateRoot, context)
        collector.collectMod(childMod)
        return childModData
    }

    private fun collectEnumAsModData(enum: RsEnumItem): ModData {
        val enumName = enum.name!!  // todo
        val enumPath = modData.path.append(enumName)
        val enumModData = ModData(
            parent = modData,
            crate = modData.crate,
            path = enumPath,
            isEnabledByCfg = modData.isEnabledByCfg && enum.isEnabledByCfgSelf(crate),
            fileId = modData.fileId,
            fileRelativePath = "${modData.fileRelativePath}::$enumName",
            ownedDirectoryId = modData.ownedDirectoryId,  // actually can use any value here
            isEnum = true
        )
        for (variant in enum.variants) {
            val variantName = variant.name ?: continue
            val variantPath = enumPath.append(variantName)
            val visItem = VisItem(variantPath, Visibility.Public)
            enumModData.visibleItems[variantName] = PerNs(visItem, variant.namespaces)
        }
        return enumModData
    }

    private fun collectMacroCall(call: RsMacroCall) {
        val isEnabledByCfg = modData.isEnabledByCfg && call.isEnabledByCfgSelf(crate)
        if (!isEnabledByCfg) return  // todo
        val body = call.includeMacroArgument?.expr?.value ?: call.macroBody ?: return
        val bodyHash = call.bodyHash
        val path = call.path.fullPath
        val dollarCrateId = call.path.getUserData(RESOLVE_DOLLAR_CRATE_ID_KEY)  // for `$crate::foo!()`
        val pathAdjusted = adjustPathWithDollarCrate(path, dollarCrateId)
        val macroDef = if (path.contains("::")) null else modData.legacyMacros[path]
        val dollarCrateMap = call.getUserData(RESOLVE_RANGE_MAP_KEY) ?: RangeMap.EMPTY
        context.macroCalls += MacroCallInfo(modData, pathAdjusted, body, bodyHash, macroDepth, macroDef, dollarCrateMap)
    }

    private fun collectMacro(macro: RsMacro) {
        val isEnabledByCfg = modData.isEnabledByCfg && macro.isEnabledByCfgSelf(crate)
        if (!isEnabledByCfg) return  // todo ?
        val name = macro.name ?: return
        // check(macro.stub != null)  // todo
        val macroBodyStubbed = macro.macroBodyStubbed ?: return
        val bodyHash = macro.bodyHash ?: return
        val macroPath = modData.path.append(name)

        modData.legacyMacros[name] = MacroInfo(modData.crate, macroPath, macroBodyStubbed, bodyHash)

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
    val usePathAdjusted = adjustPathWithDollarCrate(usePath, dollarCrateId)
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
private fun adjustPathWithDollarCrate(path: String, dollarCrateId: CratePersistentId?): String {
    if (!path.startsWith(MACRO_DOLLAR_CRATE_IDENTIFIER)) return path

    if (dollarCrateId == null) {
        RESOLVE_LOG.error("Can't find crate for path starting with \$crate: '$path'")
        return path
    }
    return path.replaceFirst(MACRO_DOLLAR_CRATE_IDENTIFIER, "$MACRO_DOLLAR_CRATE_IDENTIFIER::$dollarCrateId")
}

private fun RsVisibilityOwner?.getVisibility(
    containingMod: ModData,
    crateRoot: ModData,
    // we don't want to use `this.isEnabledByCfg`, because it can trigger resolve when accessing `superMod`
    isEnabledByCfg: Boolean
): Visibility {
    if (this == null) return Visibility.Public
    if (!isEnabledByCfg) return Visibility.CfgDisabled
    val vis = vis ?: return Visibility.Restricted(containingMod)
    return when (vis.stubKind) {
        RsVisStubKind.PUB -> Visibility.Public
        RsVisStubKind.CRATE -> Visibility.Restricted(crateRoot)
        RsVisStubKind.RESTRICTED -> {
            // https://doc.rust-lang.org/reference/visibility-and-privacy.html#pubin-path-pubcrate-pubsuper-and-pubself
            val path = vis.visRestriction!!.path
            resolveRestrictedVisibility(path, crateRoot, containingMod)
        }
    }
}

private fun resolveRestrictedVisibility(
    path: RsPath,
    crateRoot: ModData,
    containingMod: ModData
): Visibility.Restricted {
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
    return Visibility.Restricted(pathTarget)
}

private fun ModData.checkChildModulesAndVisibleItemsConsistency() {
    for ((name, childMod) in childModules) {
        assertEquals(name, childMod.name, "Inconsistent name of $childMod")
        check(visibleItems[name]?.types?.isModOrEnum == true)
        { "Inconsistent `visibleItems` and `childModules` in $this for name $name" }
    }
}

private fun ModData.getOwnedDirectory(project: Project): PsiDirectory? {
    val ownedDirectoryId = ownedDirectoryId ?: return null
    return PersistentFS.getInstance()
        .findFileById(ownedDirectoryId)
        ?.toPsiDirectory(project)
}

private fun ModData.asPsiFile(project: Project): PsiFile? =
    PersistentFS.getInstance()
        .findFileById(fileId)
        ?.toPsiFile(project)
        ?: run {
            RESOLVE_LOG.error("Can't find PsiFile for $this")
            return null
        }

/**
 * We have to use our own resolve for [RsModDeclItem],
 * because sometimes we can't find `containingMod` to set as their `context`,
 * thus default resolve will not work.
 * See [RsMacroExpansionResolveTest.`test mod declared with macro inside inline expanded mod`]
 */
private fun RsModDeclItem.resolve(modData: ModData, project: Project): RsFile? {
    val name = name ?: return null
    val containingModOwnedDirectory = modData.getOwnedDirectory(project)
    val contextualFile = modData.asPsiFile(project) ?: return null
    val modDeclData = RsModDeclItemData(
        project = project,
        name = name,
        referenceName = name,
        pathAttribute = pathAttribute,
        isLocal = false,
        containingModOwnedDirectory = containingModOwnedDirectory,
        containingModName = if (modData.isCrateRoot) "" /* will not be used */ else modData.name,
        containingModIsFile = modData.isRsFile,
        contextualFile = contextualFile,
        inCrateRoot = lazy(LazyThreadSafetyMode.NONE) { modData.isCrateRoot }
    )
    val files = collectResolveVariants(name) {
        processModDeclResolveVariants(modDeclData, it)
    }
    return files.singleOrNull() as RsFile?
}

/** Have to pass [pathAttribute], because [RsFile.pathAttribute] triggers resolve */
private fun RsMod.getOwnedDirectory(parentMod: ModData, pathAttribute: String?): PsiDirectory? {
    if (this is RsFile && name == RsConstants.MOD_RS_FILE) return parent

    val (parentDirectory, path) = if (pathAttribute != null) {
        val parentDirectory = if (parentMod.isRsFile) {
            parentMod.asPsiFile(project)?.parent
        } else {
            parentMod.getOwnedDirectory(project)
        }
        parentDirectory to pathAttribute
    } else {
        parentMod.getOwnedDirectory(project) to name
    }
    if (parentDirectory == null || path == null) return null

    // Don't use `FileUtil#getNameWithoutExtension` to correctly process relative paths like `./foo`
    val directoryPath = FileUtil.toSystemIndependentName(path).removeSuffix(".${RsFileType.defaultExtension}")
    return parentDirectory.virtualFile
        .findFileByMaybeRelativePath(directoryPath)
        ?.let(parentDirectory.manager::findDirectory)
}
