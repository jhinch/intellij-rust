/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.sdk

import com.intellij.openapi.Disposable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.Link
import com.intellij.util.text.SemVer
import org.rust.cargo.toolchain.RustToolchain
import org.rust.cargo.toolchain.Rustup
import org.rust.ide.sdk.flavors.RustupSdkFlavor
import org.rust.ide.ui.RsLayoutBuilder
import org.rust.openapiext.UiDebouncer
import org.rust.openapiext.pathToDirectoryTextField
import org.rust.stdext.toPath
import java.awt.event.ItemEvent
import javax.swing.JComponent
import javax.swing.JLabel

class RsSdkAdditionalDataPanel : Disposable {
    private var sdkHome: String? = null

    private val rustupOverrideComboBox: ComboBox<String> = ComboBox<String>().apply {
        isEditable = false

        addItemListener { event ->
            if (event.stateChange == ItemEvent.SELECTED) {
                update(refreshToolchainList = false)
            }
        }
    }

    private val rustupOverride: String?
        get() = rustupOverrideComboBox.selectedItem as? String

    private val versionLabel: JLabel = JLabel()

    private val stdlibPathField: TextFieldWithBrowseButton =
        pathToDirectoryTextField(this, "Select directory with standard library source code")

    private val downloadStdlibLink: JComponent = Link("Download via rustup", action = {
        val rustup = toolchain?.rustup() ?: return@Link
        object : Task.Backgroundable(null, "Downloading Rust standard library") {
            override fun shouldStartInBackground(): Boolean = false
            override fun onSuccess() = update(refreshToolchainList = false)
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                rustup.downloadStdlib()
            }
        }.queue()
    }).apply { isVisible = false }

    var data: RsSdkAdditionalData?
        get() {
            val toolchain = toolchain ?: return null
            val isRustupAvailable = toolchain.isRustupAvailable
            val rustupOverride = rustupOverride?.takeIf { isRustupAvailable }
            val stdlibPath = stdlibPathField.text.takeIf { !isRustupAvailable && it.isNotBlank() }
            return RsSdkAdditionalData(rustupOverride, stdlibPath)
        }
        set(value) {
            with(rustupOverrideComboBox) {
                isEditable = true
                selectedItem = value?.rustupOverride
                isEditable = false
            }
            stdlibPathField.text = value?.explicitPathToStdlib ?: ""
            update(refreshToolchainList = true)
        }

    private val toolchain: RustToolchain?
        get() {
            val homePath = sdkHome?.toPath() ?: return null
            return RustToolchain(homePath, rustupOverride)
        }

    private val updateDebouncer: UiDebouncer = UiDebouncer(this)

    fun attachTo(layout: RsLayoutBuilder) = with(layout) {
        row("Rustup override:", rustupOverrideComboBox)
        row("Toolchain version:", versionLabel)
        row("Standard library:", stdlibPathField)
        row(component = downloadStdlibLink)
    }

    @Throws(ConfigurationException::class)
    fun validateSettings() {
        val toolchain = toolchain
        when {
            toolchain == null ->
                throw ConfigurationException("Invalid toolchain")
            !toolchain.looksLikeValidToolchain() ->
                throw ConfigurationException("Invalid toolchain location: can't find Cargo in ${toolchain.location}")
        }
    }

    fun notifySdkHomeChanged(sdkHome: String?) {
        if (this.sdkHome == sdkHome) return
        this.sdkHome = sdkHome
        update(refreshToolchainList = true)
    }


    private fun update(refreshToolchainList: Boolean) {

        data class Data(
            val rustcVersion: SemVer?,
            val stdlibPath: String?,
            val toolchains: List<Rustup.Toolchain>?,
            val isRustupAvailable: Boolean
        )

        updateDebouncer.run(
            onPooledThread = {
                val toolchain = toolchain
                val rustup = toolchain?.rustup()
                val rustcVersion = toolchain?.queryVersions()?.rustc?.semver
                val stdlibLocation = toolchain?.getStdlibFromSysroot()?.presentableUrl
                val toolchains = if (refreshToolchainList) rustup?.listToolchains().orEmpty() else null
                Data(rustcVersion, stdlibLocation, toolchains, rustup != null)
            },
            onUiThread = { (rustcVersion, stdlibLocation, toolchains, isRustupAvailable) ->
                if (rustcVersion == null) {
                    versionLabel.text = "N/A"
                    versionLabel.foreground = JBColor.RED
                } else {
                    versionLabel.text = rustcVersion.parsedVersion
                    versionLabel.foreground = JBColor.foreground()
                }

                stdlibPathField.isEditable = !isRustupAvailable
                stdlibPathField.button.isEnabled = !isRustupAvailable
                if (stdlibLocation != null) {
                    stdlibPathField.text = stdlibLocation
                }

                downloadStdlibLink.isVisible = isRustupAvailable && stdlibLocation == null

                if (toolchains != null) {
                    val oldSelection = rustupOverrideComboBox.selectedItem

                    rustupOverrideComboBox.removeAllItems()
                    toolchains.forEach { rustupOverrideComboBox.addItem(it.name) }

                    rustupOverrideComboBox.selectedItem = when {
                        toolchains.any { it.name == oldSelection } -> oldSelection
                        toolchains.any { it.isDefault } -> toolchains.first { it.isDefault }
                        else -> toolchains.firstOrNull()
                    }

                    rustupOverrideComboBox.isEnabled = isRustupAvailable
                }
            }
        )
    }

    override fun dispose() {}

    companion object {
        fun validateSdkAdditionalDataPanel(panel: RsSdkAdditionalDataPanel): ValidationInfo? {
            val homePath = panel.sdkHome ?: return null
            if (!RustupSdkFlavor.isValidSdkPath(homePath)) return null
            if (panel.rustupOverride != null) return null
            return ValidationInfo("Rustup override is not selected", panel.rustupOverrideComboBox)
        }
    }
}
