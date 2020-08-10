/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.sdk

import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModificator
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import org.rust.cargo.toolchain.RustToolchain
import org.rust.ide.sdk.flavors.RsSdkFlavor
import org.rust.ide.sdk.flavors.RustupSdkFlavor
import org.rust.openapiext.computeWithCancelableProgress
import org.rust.openapiext.pathAsPath
import org.rust.stdext.toPath
import java.nio.file.Path

//var Project.rustSdk: Sdk?
//    get() {
//        val sdk = ProjectRootManager.getInstance(this).projectSdk
//        return sdk?.takeIf { it.sdkType is RsSdkType }
//    }
//    set(value) {
//        invokeAndWaitIfNeeded { setDirectoryProjectSdk(this, value) }
//    }
//
//var Module.rustSdk: Sdk?
//    get() {
//        val sdk = ModuleRootManager.getInstance(this).sdk
//        return sdk?.takeIf { it.sdkType is RsSdkType }
//    }
//    set(value) {
//        // TODO
//        ModuleRootModificationUtil.setModuleSdk(this, value)
//    }

fun Sdk.modify(action: (SdkModificator) -> Unit) {
    val sdkModificator = sdkModificator
    action(sdkModificator)
    sdkModificator.commitChanges()
}

val Sdk.toolchain: RustToolchain?
    get() {
        val homePath = homePath?.toPath() ?: return null
        val rustupOverride = rustData?.rustupOverride
        return RustToolchain(homePath, rustupOverride)
    }

val Sdk.explicitPathToStdlib: String? get() = rustData?.explicitPathToStdlib

private val Sdk.rustData: RsSdkAdditionalData?
    get() = sdkAdditionalData as? RsSdkAdditionalData

object RsSdkUtils {

    fun isInvalid(sdk: Sdk): Boolean {
        val toolchain = sdk.homeDirectory
        return toolchain == null || !toolchain.exists()
    }

    fun getAllRustSdks(): List<Sdk> =
        ProjectJdkTable.getInstance().getSdksOfType(RsSdkType.getInstance())

    fun findSdkByName(name: String): Sdk? =
        ProjectJdkTable.getInstance().findJdk(name, RsSdkType.getInstance().name)

    fun detectRustSdks(
        existingSdks: List<Sdk>,
        flavors: List<RsSdkFlavor> = RsSdkFlavor.getApplicableFlavors()
    ): List<RsDetectedSdk> {
        val existingPaths = existingSdks
            .mapNotNull { it.homePath }
            .filterNot { RustupSdkFlavor.isValidSdkPath(it) }
        return flavors.asSequence()
            .flatMap { it.suggestHomePaths() }
            .map { it.absolutePath }
            .distinct()
            .filterNot { it in existingPaths }
            .map { RsDetectedSdk(it) }
            .toList()
    }

    fun findOrCreateSdk(): Sdk? {
        val defaultProject = ProjectManager.getInstance().defaultProject
        val sdk = ProjectRootManager.getInstance(defaultProject).projectSdk
        if (sdk?.sdkType is RsSdkType) return sdk

        val sdks = getAllRustSdks().sortedWith(RsSdkComparator)
        if (sdks.isNotEmpty()) return sdks.first()

        val existingSdks = ProjectJdkTable.getInstance().allJdks
        for (homePath in RsSdkType.getInstance().suggestHomePaths()) {
            val sdkHome = LocalFileSystem.getInstance().refreshAndFindFileByPath(homePath) ?: continue
            val newSdk = setupSdk(existingSdks, sdkHome, null) ?: continue
            SdkConfigurationUtil.addSdk(newSdk)
            return newSdk
        }

        return null
    }

    fun setupSdk(existingSdks: Array<Sdk>, homeDir: VirtualFile, additionalData: RsSdkAdditionalData?): Sdk? {
        val effectiveData = additionalData ?: createAdditionalData(homeDir.pathAsPath) ?: return null
        val sdkType = RsSdkType.getInstance()
        val suggestedName = buildString {
            append(sdkType.suggestSdkName(null, homeDir.path))
            effectiveData.rustupOverride?.let { append(" ($it)") }
        }
        return SdkConfigurationUtil.setupSdk(existingSdks, homeDir, sdkType, true, effectiveData, suggestedName)
    }

    private fun createAdditionalData(sdkPath: Path): RsSdkAdditionalData? {
        val data = RsSdkAdditionalData()
        val rustup = RustToolchain(sdkPath, null).rustup()
        if (rustup != null) {
            val project = ProjectManager.getInstance().defaultProject
            data.rustupOverride = project.computeWithCancelableProgress("Fetching default toolchain...") {
                rustup.listToolchains().find { it.isDefault }?.name
            } ?: return null
        }
        return data
    }
}
