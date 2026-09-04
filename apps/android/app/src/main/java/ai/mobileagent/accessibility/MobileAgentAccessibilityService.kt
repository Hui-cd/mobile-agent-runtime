package ai.mobileagent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import ai.mobileagent.model.Observation
import ai.mobileagent.model.UiNodeSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

class MobileAgentAccessibilityService : AccessibilityService() {
    private var lastExternalSnapshotAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityBridge.service = this
        AccessibilityBridge.setConnected(true)
        Log.i("MobileAgent", "Accessibility service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AccessibilityBridge.service = null
        AccessibilityBridge.setConnected(false)
        Log.i("MobileAgent", "Accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackage = event?.packageName?.toString() ?: return
        if (eventPackage == packageName) return
        val now = System.currentTimeMillis()
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && now - lastExternalSnapshotAt < 1_000) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() == packageName) return
        val nodes = mutableListOf<UiNodeSnapshot>()
        flatten(root, nodes, 300)
        AccessibilityBridge.lastExternalObservation = Observation(
            packageName = root.packageName?.toString(),
            className = root.className?.toString(),
            nodes = nodes,
        )
        lastExternalSnapshotAt = now
    }
    override fun onInterrupt() = Unit

    suspend fun observe(includeScreen: Boolean = false): Observation = withContext(Dispatchers.Main) {
        val root = rootInActiveWindow
        val nodes = mutableListOf<UiNodeSnapshot>()
        if (root != null) flatten(root, nodes, 300)
        Observation(
            packageName = root?.packageName?.toString(),
            className = root?.className?.toString(),
            nodes = nodes,
            screenshotDataUrl = if (includeScreen) captureScreenshot() else null,
        ).also { observation ->
            if (observation.packageName != null && observation.packageName != packageName) {
                AccessibilityBridge.lastExternalObservation = observation.copy(screenshotDataUrl = null)
            }
        }
    }

    private fun flatten(node: AccessibilityNodeInfo, output: MutableList<UiNodeSnapshot>, max: Int) {
        if (output.size >= max) return
        val rect = Rect().also(node::getBoundsInScreen)
        val className = node.className?.toString().orEmpty()
        output += UiNodeSnapshot(
            index = output.size,
            text = node.text?.toString()?.takeIf(String::isNotBlank),
            contentDescription = node.contentDescription?.toString()?.takeIf(String::isNotBlank),
            resourceId = node.viewIdResourceName,
            role = role(className, node),
            className = className,
            clickable = node.isClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            enabled = node.isEnabled,
            bounds = intArrayOf(rect.left, rect.top, rect.right, rect.bottom),
        )
        for (index in 0 until node.childCount) node.getChild(index)?.let { flatten(it, output, max) }
    }

    private fun role(className: String, node: AccessibilityNodeInfo): String = when {
        node.isEditable || className.contains("EditText") -> "text_field"
        className.contains("Button") -> "button"
        className.contains("Switch") -> "switch"
        className.contains("CheckBox") -> "checkbox"
        className.contains("RecyclerView") || className.contains("ListView") -> "list"
        node.isScrollable -> "scroll_view"
        className.contains("TextView") -> "text"
        else -> "view"
    }

    private fun allNodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || result.size >= 1000) return
            result += node
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(rootInActiveWindow)
        return result
    }

    private fun matches(node: AccessibilityNodeInfo, target: org.json.JSONObject): Boolean {
        if (target.has("text") && node.text?.toString() != target.optString("text")) return false
        if (target.has("content_description") && node.contentDescription?.toString() != target.optString("content_description")) return false
        if (target.has("resource_id") && node.viewIdResourceName != target.optString("resource_id")) return false
        if (target.has("role") && role(node.className?.toString().orEmpty(), node) != target.optString("role")) return false
        return true
    }

    private fun findNode(target: org.json.JSONObject): AccessibilityNodeInfo? {
        val nodes = allNodes()
        if (target.has("index")) return nodes.getOrNull(target.optInt("index"))
        return nodes.firstOrNull { matches(it, target) && it.isEnabled }
    }

    suspend fun act(arguments: org.json.JSONObject): Observation = withContext(Dispatchers.Main) {
        val action = arguments.getString("action")
        val target = arguments.optJSONObject("target") ?: org.json.JSONObject()
        when (action) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "click" -> {
                val node = findNode(target) ?: error("TARGET_NOT_FOUND")
                var current: AccessibilityNodeInfo? = node
                var clicked = false
                while (current != null && !clicked) {
                    clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    current = current.parent
                }
                if (!clicked) gestureAt(node, 100)
            }
            "long_press" -> gestureAt(findNode(target) ?: error("TARGET_NOT_FOUND"), arguments.optLong("duration_ms", 700))
            "input" -> {
                val node = findNode(if (target.length() == 0) org.json.JSONObject().put("role", "text_field") else target)
                    ?: error("TARGET_NOT_FOUND")
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val bundle = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, arguments.getString("value"))
                }
                check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)) { "INPUT_FAILED" }
            }
            "scroll" -> {
                val node = findNode(target.takeIf { it.length() > 0 } ?: org.json.JSONObject().put("role", "scroll_view"))
                    ?: allNodes().firstOrNull { it.isScrollable } ?: error("SCROLL_TARGET_NOT_FOUND")
                val direction = arguments.optString("direction", "up")
                val code = if (direction == "up" || direction == "left") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                if (!node.performAction(code)) {
                    swipe(direction, arguments.optLong("duration_ms", 500))
                }
            }
            "swipe" -> swipe(arguments.optString("direction", "up"), arguments.optLong("duration_ms", 500))
            else -> error("UNSUPPORTED_ACTION: $action")
        }
        delay(700)
        observe(arguments.optBoolean("include_screen", false))
    }

    private suspend fun gestureAt(node: AccessibilityNodeInfo, duration: Long) {
        val rect = Rect().also(node::getBoundsInScreen)
        val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
        dispatch(path, duration)
    }

    private suspend fun swipe(direction: String, duration: Long) {
        val metrics = resources.displayMetrics
        val cx = metrics.widthPixels / 2f
        val cy = metrics.heightPixels / 2f
        val dx = metrics.widthPixels * .3f
        val dy = metrics.heightPixels * .3f
        val (start, end) = when (direction) {
            "down" -> Pair(cx to cy - dy, cx to cy + dy)
            "left" -> Pair(cx + dx to cy, cx - dx to cy)
            "right" -> Pair(cx - dx to cy, cx + dx to cy)
            else -> Pair(cx to cy + dy, cx to cy - dy)
        }
        val path = Path().apply { moveTo(start.first, start.second); lineTo(end.first, end.second) }
        dispatch(path, duration)
    }

    private suspend fun dispatch(path: Path, duration: Long) = suspendCancellableCoroutine { continuation ->
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(Unit) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(Unit) }
        }, null)
        if (!accepted && continuation.isActive) continuation.resume(Unit)
    }

    private suspend fun captureScreenshot(): String? = suspendCancellableCoroutine { continuation ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                result.hardwareBuffer.close()
                if (bitmap == null) { continuation.resume(null); return }
                val scale = minOf(1f, 1080f / bitmap.width)
                val output = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
                val bytes = ByteArrayOutputStream().also { output.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
                continuation.resume("data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
            }
            override fun onFailure(errorCode: Int) { continuation.resume(null) }
        })
    }
}
