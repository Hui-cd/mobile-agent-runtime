package ai.mobileagent.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import ai.mobileagent.model.Observation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object AccessibilityBridge {
    @Volatile var service: MobileAgentAccessibilityService? = null
        internal set
    @Volatile var lastExternalObservation: Observation? = null
        internal set

    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()

    internal fun setConnected(value: Boolean) { _connected.value = value }

    fun refresh(context: Context) {
        _connected.value = service != null || isEnabled(context)
    }

    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, MobileAgentAccessibilityService::class.java)
        val configured = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty().split(':').mapNotNull(ComponentName::unflattenFromString).any { it == expected }
        if (configured) return true
        val manager = context.getSystemService(AccessibilityManager::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    }
}
