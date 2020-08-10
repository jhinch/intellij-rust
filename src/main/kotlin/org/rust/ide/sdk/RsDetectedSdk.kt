/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.sdk

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import org.rust.ide.sdk.RsSdkUtils.setupSdk

class RsDetectedSdk(homePath: String) : ProjectJdkImpl(homePath, RsSdkType.getInstance()) {

    init {
        this.homePath = homePath
    }

    override fun getVersionString(): String? = ""

    fun setup(existingSdks: List<Sdk>, additionalData: RsSdkAdditionalData): Sdk? {
        val homeDirectory = homeDirectory ?: return null
        return setupSdk(existingSdks.toTypedArray(), homeDirectory, additionalData)
    }
}
