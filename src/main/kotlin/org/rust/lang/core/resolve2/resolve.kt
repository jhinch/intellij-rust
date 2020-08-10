/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapi.diagnostic.Logger
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.lang.core.crate.CratePersistentId
import org.rust.lang.core.psi.ext.RsMod
import org.rust.lang.core.psi.ext.containingCrate
import org.rust.lang.core.psi.ext.superMods
import org.rust.lang.core.resolve.Namespace

class DefDatabase(
    /** `DefMap`s for some crate and all its dependencies (including transitive) */
    val allDefMaps: Map<CratePersistentId, CrateDefMap>
) {
    fun getModData(modPath: ModPath): ModData? {
        val defMap = allDefMaps[modPath.crate]
            ?: error("todo")
        return defMap.getModData(modPath)
    }

    fun tryCastToModData(perNs: PerNs): ModData? {
        val types = perNs.types ?: return null
        return tryCastToModData(types)
    }

    fun tryCastToModData(types: VisItem): ModData? {
        if (!types.isModOrEnum) return null
        return getModData(types.path)
    }

    fun getMacroInfo(macroDef: VisItem): MacroInfo {
        val defMap = allDefMaps[macroDef.crate]!!
        return defMap.getMacroInfo(macroDef)
    }
}

// todo вынести поля нужные только на этапе построения в collector ?
class CrateDefMap(
    val crate: CratePersistentId,
    val edition: CargoWorkspace.Edition,
    val root: ModData,

    val externPrelude: MutableMap<String, ModData>,
    // used only by `extern crate crate_name;` declarations
    val directDependenciesDefMaps: Map<String, CrateDefMap>,
    allDependenciesDefMaps: Map<CratePersistentId, CrateDefMap>,
    var prelude: ModData?,
    val crateDescription: String  // only for debug
) {
    val defDatabase: DefDatabase = DefDatabase(allDependenciesDefMaps + (crate to this))

    fun getModData(modPath: ModPath): ModData? {
        check(crate == modPath.crate)
        return modPath.segments
            // todo assert not null ?
            .fold(root as ModData?) { modData, segment -> modData?.childModules?.get(segment) }
    }

    // todo move to facade.rs вместе с ModPath.fromMod
    fun getModData(mod: RsMod): ModData? {
        mod.containingCrate?.id?.let { check(it == crate) }  // todo
        val modPath = ModPath.fromMod(mod, crate) ?: return null
        return getModData(modPath)
    }

    // todo - создать ещё одну Map вроде legacyMacros, в которой будут записаны только явно объявленные макросы?
    fun getMacroInfo(macroDef: VisItem): MacroInfo {
        val containingMod = getModData(macroDef.containingMod)!!
        return containingMod.legacyMacros[macroDef.name]!!
    }

    /**
     * Import all exported macros from another crate.
     *
     * Exported macros are just all macros in the root module scope.
     * Note that it contains not only all `#[macro_export]` macros, but also all aliases
     * created by `use` in the root module, ignoring the visibility of `use`.
     */
    fun importAllMacrosExported(from: CrateDefMap) {
        for ((name, def) in from.root.visibleItems) {
            val macroDef = def.macros ?: continue
            // `macro_use` only bring things into legacy scope.
            root.legacyMacros[name] = defDatabase.getMacroInfo(macroDef)
        }
    }

    override fun toString(): String = crateDescription
}

class ModData(
    // todo три мапы ?
    val visibleItems: MutableMap<String, PerNs>,
    val parent: ModData?,
    val crate: CratePersistentId,
    val path: ModPath,
    /** id of containing file */
    val fileId: Int,
    // todo тип? String / List<String> / ModPath
    val fileRelativePath: String,  // starts with ::
    val isEnabledByCfg: Boolean,
    val isEnum: Boolean = false
) {
    val name: String get() = path.name
    val isCrateRoot: Boolean get() = parent == null
    val parents: Sequence<ModData> get() = generateSequence(this) { it.parent }

    val childModules: MutableMap<String, ModData> = hashMapOf()

    /**
     * Macros visible in current module in legacy textual scope
     * Module scoped macros will be inserted into [visibleItems] instead of here.
     */
    // todo visibility ?
    // todo currently stores only cfg-enabled macros
    val legacyMacros: MutableMap<String, MacroInfo> = hashMapOf()

    /** Traits imported via `use Trait as _;` */
    val unnamedTraitImports: MutableMap<ModPath, Visibility> = hashMapOf()

    operator fun get(name: String): PerNs = visibleItems.getOrDefault(name, PerNs.Empty)

    fun getVisibleItems(filterVisibility: (Visibility) -> Boolean): List<Pair<String, PerNs>> {
        val usualItems = visibleItems.entries
            .map { (name, visItem) -> name to visItem.filterVisibility(filterVisibility) }
            .filterNot { (_, visItem) -> visItem.isEmpty }
        val traitItems = unnamedTraitImports
            .mapNotNull { (path, visibility) ->
                if (!filterVisibility(visibility)) return@mapNotNull null
                val trait = VisItem(path, visibility, isModOrEnum = false)
                "_" to PerNs(types = trait)
            }
        return usualItems + traitItems
    }

    fun addVisibleItem(name: String, perNs: PerNs) {
        val perNsExisting = visibleItems.getOrPut(name, { PerNs() })
        perNsExisting.update(perNs)
    }

    fun asVisItem(): VisItem {
        val parent = parent
        return if (parent == null) {
            // crate root
            VisItem(path, Visibility.Public, true)
        } else {
            parent.visibleItems[name]?.types?.takeIf { it.isModOrEnum }
                ?: error("Inconsistent `visibleItems` and `childModules` in parent of $this")
        }
    }

    fun asPerNs(): PerNs = PerNs(types = asVisItem())

    fun getNthParent(n: Int): ModData? {
        check(n >= 0)
        return parents.drop(n).firstOrNull()
    }

    override fun toString(): String = "ModData(path=$path, crate=$crate)"
}

data class PerNs(
    // todo var ?
    var types: VisItem? = null,
    var values: VisItem? = null,
    var macros: VisItem? = null
    // todo
    // val invalid: List<ModPath>
) {
    val isEmpty: Boolean get() = types == null && values == null && macros == null

    constructor(visItem: VisItem, ns: Set<Namespace>) :
        this(
            visItem.takeIf { Namespace.Types in ns },
            visItem.takeIf { Namespace.Values in ns },
            visItem.takeIf { Namespace.Macros in ns }
        )

    fun withVisibility(visibility: Visibility): PerNs =
        PerNs(
            types?.withVisibility(visibility),
            values?.withVisibility(visibility),
            macros?.withVisibility(visibility)
        )

    fun filterVisibility(filter: (Visibility) -> Boolean): PerNs =
        PerNs(
            types?.takeIf { filter(it.visibility) },
            values?.takeIf { filter(it.visibility) },
            macros?.takeIf { filter(it.visibility) }
        )

    // todo объединить с DefCollector#pushResolutionFromImport ?
    fun update(other: PerNs) {
        // todo multiresolve
        fun merge(existing: VisItem?, new: VisItem?): VisItem? {
            if (existing == null) return new
            if (new == null) return existing
            return if (new.visibility.isStrictMorePermissive(existing.visibility)) new else existing
        }
        types = merge(types, other.types)
        values = merge(values, other.values)
        macros = merge(macros, other.macros)
    }

    fun or(other: PerNs): PerNs =
        PerNs(
            types ?: other.types,
            values ?: other.values,
            macros ?: other.macros
        )

    fun mapItems(f: (VisItem) -> VisItem): PerNs =
        PerNs(
            types?.let { f(it) },
            values?.let { f(it) },
            macros?.let { f(it) }
        )

    companion object {
        val Empty: PerNs = PerNs()
    }
}

/**
 * The item which can be visible in the module (either directly declared or imported)
 * Could be [RsEnumVariant] (because it can be imported)
 */
data class VisItem(
    /**
     * Full path to item, including its name.
     * Note: Can't store [containingMod] and [name] separately, because [VisItem] could be used for crate root
     */
    val path: ModPath,
    val visibility: Visibility,
    val isModOrEnum: Boolean = false
) {
    val containingMod: ModPath get() = path.parent  // mod where item is explicitly declared
    val name: String get() = path.name
    val crate: CratePersistentId get() = path.crate

    fun withVisibility(visibilityNew: Visibility): VisItem =
        if (visibility == visibilityNew || visibility.isInvisible) this else copy(visibility = visibilityNew)

    override fun toString(): String = "$visibility $path"
}

sealed class Visibility {
    fun isVisibleFromOtherCrate(): Boolean = this === Public

    fun isVisibleFromMod(mod: ModData): Boolean =
        when (this) {
            Public -> true
            is Restricted -> mod.parents.contains(inMod)
            Invisible, CfgDisabled -> false
        }

    object Public : Visibility()

    /** includes [Private] */
    class Restricted(val inMod: ModData) : Visibility()

    /**
     * Means that we have import to private item
     * So normally we should ignore such [VisItem] (it is not accessible)
     * But we record it for completion, etc
     */
    object Invisible : Visibility()

    object CfgDisabled : Visibility()

    fun isStrictMorePermissive(other: Visibility): Boolean {
        return if (this is Restricted && other is Restricted) {
            inMod.crate == other.inMod.crate
                && inMod !== other.inMod
                && other.inMod.parents.contains(inMod)
        } else {
            when (this) {
                Public -> other !is Public
                is Restricted -> other === Invisible || other === CfgDisabled
                Invisible -> other === CfgDisabled
                CfgDisabled -> false
            }
        }
    }

    val isInvisible: Boolean get() = this === Invisible || this === CfgDisabled

    override fun toString(): String =
        when (this) {
            Public -> "Public"
            is Restricted -> "Restricted(in ${inMod.path})"
            Invisible -> "Invisible"
            CfgDisabled -> "CfgDisabled"
        }
}

/** Path to a module or an item in module */
data class ModPath(
    val crate: CratePersistentId,
    val segments: List<String>
    // val fileId: Int,  // id of containing file
    // val fileRelativePath: String  // empty for pathRsFile
) {
    val path: String get() = segments.joinToString("::")
    val name: String get() = segments.last()
    val parent: ModPath get() = ModPath(crate, segments.subList(0, segments.size - 1))

    fun append(segment: String): ModPath = ModPath(crate, segments + segment)

    override fun toString(): String = path.ifEmpty { "crate" }

    companion object {
        fun fromMod(mod: RsMod, crate: CratePersistentId): ModPath? {
            val segments = mod.superMods
                .asReversed().drop(1)
                .map { it.modName ?: return null }
            return ModPath(crate, segments)
        }
    }
}

val RESOLVE_LOG = Logger.getInstance("org.rust.resolve")
