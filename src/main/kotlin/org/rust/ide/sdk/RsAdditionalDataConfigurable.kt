/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.sdk

import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.projectRoots.AdditionalDataConfigurable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.util.Disposer
import org.rust.ide.ui.layout
import javax.swing.JComponent

class RsAdditionalDataConfigurable(private val sdkModel: SdkModel) : AdditionalDataConfigurable {
    private lateinit var editableSdk: Sdk

    private val sdkAdditionalDataPanel: RsSdkAdditionalDataPanel = RsSdkAdditionalDataPanel()

    private val sdkListener: SdkModel.Listener = object : SdkModel.Listener {
        override fun sdkHomeSelected(sdk: Sdk, newSdkHome: String) {
            if (sdk.name == editableSdk.name) {
                sdkAdditionalDataPanel.notifySdkHomeChanged(newSdkHome)
            }
        }
    }

    init {
        sdkModel.addListener(sdkListener)
    }

    override fun setSdk(sdk: Sdk) {
        this.editableSdk = sdk
        sdkAdditionalDataPanel.data = sdk.sdkAdditionalData as? RsSdkAdditionalData
        sdkAdditionalDataPanel.notifySdkHomeChanged(sdk.homePath)
    }

    override fun createComponent(): JComponent = layout {
        sdkAdditionalDataPanel.attachTo(this)
    }

    override fun isModified(): Boolean {
        val sdkAdditionalData = editableSdk.sdkAdditionalData
        val data = sdkAdditionalDataPanel.data
        return sdkAdditionalData != data
    }

    override fun apply() {
        sdkAdditionalDataPanel.validateSettings()
        editableSdk.modify { it.sdkAdditionalData = sdkAdditionalDataPanel.data }
        for (project in ProjectManager.getInstance().openProjects) {
            project.messageBus.syncPublisher(RsSdkAdditionalData.RUST_ADDITIONAL_DATA_TOPIC)
                .sdkAdditionalDataChanged(editableSdk)
        }
    }

    override fun reset() {
        sdkAdditionalDataPanel.data = editableSdk.sdkAdditionalData as? RsSdkAdditionalData
    }

    override fun disposeUIResources() {
        Disposer.dispose(sdkAdditionalDataPanel)
        sdkModel.removeListener(sdkListener)
    }
}
