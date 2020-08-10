/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.sdk.flavors

import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import java.io.File

// TODO: remove before merge
class RustupToolchainSdkFlavor private constructor() : RsSdkFlavor {

    override fun getHomePathCandidates(): Sequence<File> {
        val toolchains = File(FileUtil.expandUserHome("~/.rustup/toolchains"))
        if (!toolchains.exists() || !toolchains.isDirectory) return emptySequence()
        return toolchains.listFiles { file -> file.isDirectory }.asSequence()
            .map { File(it, "bin") }
            .filter { it.isDirectory }
    }

    override fun isApplicable(): Boolean = SystemInfo.isUnix

    companion object {
        fun getInstance(): RsSdkFlavor? = RsSdkFlavor.EP_NAME.findExtension(RustupToolchainSdkFlavor::class.java)
    }
}
