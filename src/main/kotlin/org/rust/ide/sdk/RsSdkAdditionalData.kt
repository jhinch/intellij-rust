/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.sdk

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkAdditionalData
import com.intellij.util.messages.Topic
import org.jdom.Element

data class RsSdkAdditionalData(
    var rustupOverride: String? = null,
    var explicitPathToStdlib: String? = null
) : SdkAdditionalData {

    fun save(rootElement: Element) {
        rustupOverride?.let { rootElement.setAttribute(RUSTUP_OVERRIDE, it) }
        explicitPathToStdlib?.let { rootElement.setAttribute(STDLIB_PATH, it) }
    }

    fun load(element: Element?) {
        if (element == null) return
        rustupOverride = element.getAttributeValue(RUSTUP_OVERRIDE)
        explicitPathToStdlib = element.getAttributeValue(STDLIB_PATH)
    }

    interface Listener {
        fun sdkAdditionalDataChanged(sdk: Sdk)
    }

    companion object {
        private const val RUSTUP_OVERRIDE: String = "RUSTUP_OVERRIDE"
        private const val STDLIB_PATH: String = "STDLIB_PATH"

        val RUST_ADDITIONAL_DATA_TOPIC: Topic<Listener> = Topic(
            "rust sdk additional data changes",
            Listener::class.java
        )
    }
}
