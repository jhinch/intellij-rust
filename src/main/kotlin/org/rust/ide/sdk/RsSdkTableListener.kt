/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.sdk

import com.intellij.execution.RunManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import org.rust.cargo.project.settings.rustSettings
import org.rust.cargo.runconfig.command.CargoCommandConfiguration
import org.rust.cargo.runconfig.command.CargoCommandConfigurationType

class RsSdkTableListener(private val project: Project) : ProjectJdkTable.Listener {

    override fun jdkNameChanged(jdk: Sdk, previousName: String) {
        if (jdk.sdkType !is RsSdkType) return

        project.rustSettings.modify {
            if (it.sdkName == previousName) {
                it.sdkName = jdk.name
            }
        }

        val runManager = RunManager.getInstance(project)
        val configurations = runManager.getConfigurationsList(CargoCommandConfigurationType.getInstance())
        for (configuration in configurations) {
            if (configuration is CargoCommandConfiguration && configuration.sdkName == previousName) {
                configuration.sdkName = jdk.name
            }
        }
    }

    override fun jdkAdded(jdk: Sdk) {}

    override fun jdkRemoved(jdk: Sdk) {}
}
