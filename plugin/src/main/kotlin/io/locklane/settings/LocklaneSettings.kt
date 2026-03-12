package io.locklane.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.PROJECT)
@State(name = "LocklaneSettings", storages = [Storage("locklane.xml")])
class LocklaneSettings : PersistentStateComponent<LocklaneSettings.State> {

    data class State(
        var pythonPath: String = "",
        var resolverPreference: String = "uv",
        var extraIndexUrls: MutableList<String> = mutableListOf(),
        var verifyCommand: String = "",
        var timeoutSeconds: Int = 120,
        var resolverSourcePath: String = "",
        var autoScanEnabled: Boolean = true,
        var lastManifestPath: String = "",
        var ignoredPackages: MutableList<String> = mutableListOf(),
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    companion object {
        fun getInstance(project: Project): LocklaneSettings =
            project.getService(LocklaneSettings::class.java)
    }
}
