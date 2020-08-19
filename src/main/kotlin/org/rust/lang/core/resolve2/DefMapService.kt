/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import org.rust.lang.core.crate.CratePersistentId
import org.rust.lang.core.psi.RsFile
import java.util.concurrent.ConcurrentHashMap

// todo разделить interface и impl
// todo @Synchronized
class DefMapService {
    // `null` value means there was attempt to build DefMap
    val defMaps: MutableMap<CratePersistentId, CrateDefMap?> = ConcurrentHashMap()

    /**
     * todo update comment
     * Contains [PsiFile.getModificationStamp] for rust files of all crates.
     * Note: [VirtualFile.getModificationStamp] changes only after file is saved to disk.
     * Todo: Not working after IDE restart ?
     */
    val fileModificationStamps: MutableMap<FileId, Pair<Long, CratePersistentId>> = ConcurrentHashMap()

    // todo name ?
    @Volatile
    private var changedFiles: MutableSet<RsFile> = hashSetOf()

    private val changedCrates: MutableSet<CratePersistentId> = hashSetOf()

    @Synchronized
    fun onFileChanged(file: RsFile) {
        changedFiles.add(file)
    }

    @Synchronized
    fun takeChangedFiles(): Set<RsFile> {
        // todo сделать как в takeChangedCrates ?
        val changedFiles = changedFiles
        this.changedFiles = hashSetOf()
        return changedFiles
    }

    @Synchronized
    fun hasChangedFiles(): Boolean = changedFiles.isNotEmpty()

    @Synchronized  // todo use different locks for files and crates
    fun addChangedCrates(changedCrates: Collection<CratePersistentId>) {
        this.changedCrates.addAll(changedCrates)
    }

    @Synchronized
    fun takeChangedCrates(): Set<CratePersistentId> =
        changedCrates.toHashSet()
            .also { changedCrates.clear() }

    @Synchronized
    fun getChangedCrates(): Set<CratePersistentId> = changedCrates.toHashSet()
}

val Project.defMapService: DefMapService
    get() = service()
