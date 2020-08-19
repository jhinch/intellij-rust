/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.util.io.DigestUtil
import org.rust.stdext.HashCode
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream

fun calculateHashForAllFiles(defMap: CrateDefMap, context: CollectorContext) {
    val importsAndMacros = ImportsAndMacrosGrouped(context)
    defMap.root.visitDescendantModules { modData ->
        if (!modData.isRsFile) return@visitDescendantModules true
        val fileInfo = defMap.fileInfos[modData.fileId] ?: error("Missing fileInfo for $modData")
        fileInfo.hash = calculateFileHash(modData, importsAndMacros)
        true
    }
}

/**
 * Calculates hash of file corresponding to [modData].
 * [context] contains all imports and macro calls inside that file.
 */
fun calculateFileHash(modData: ModData, context: CollectorContext): HashCode {
    val importsAndMacros = ImportsAndMacrosGrouped(context)
    return calculateFileHash(modData, importsAndMacros)
}

private fun calculateFileHash(modData: ModData, importsAndMacros: ImportsAndMacrosGrouped): HashCode {
    val digest = DigestUtil.sha1()
    val delimiter = 0

    val data = DataOutputStream(/* todo buffer? */ DigestOutputStream(OutputStream.nullOutputStream(), digest))

    check(modData.isRsFile)
    modData.visitDescendantModules {
        if (it.fileId != modData.fileId) return@visitDescendantModules false

        val visibleItems = it.visibleItems.toSortedMap()
        for ((name, perNs) in visibleItems) {
            data.writeUTF(name)
            perNs.writeTo(data)
        }
        data.writeByte(delimiter)

        importsAndMacros.imports[it]?.let { imports ->  // todo sorting
            for (import in imports) {
                import.writeTo(data)
            }
        }
        data.writeByte(delimiter)

        importsAndMacros.macroCalls[it]?.let { macroCalls ->  // todo sorting
            for (import in macroCalls) {
                import.writeTo(data)
            }
        }
        data.writeByte(delimiter)

        true
    }

    return HashCode.fromByteArray(digest.digest())
}

private class ImportsAndMacrosGrouped(
    val imports: Map<ModData, List<Import>>,
    val macroCalls: Map<ModData, List<MacroCallInfo>>
) {
    constructor(context: CollectorContext) : this(
        context.imports.groupBy { it.containingMod },
        context.macroCalls.groupBy { it.containingMod }
    )
}

private fun PerNs.writeTo(data: DataOutputStream) {
    fun DataOutputStream.write(item: VisItem?) {
        // todo write three booleans as one byte
        writeBoolean(item == null)
        item?.writeTo(this)
    }
    data.write(types)
    data.write(values)
    data.write(macros)
}

private fun VisItem.writeTo(data: DataOutputStream) {
    path.writeTo(data, withCrate = false)
    visibility.writeTo(data, withCrate = false)
    // todo remove
    data.writeBoolean(isModOrEnum)
}
