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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MobileAgentAccessibilityService : AccessibilityService() {
    private var lastExternalSnapshotAt = 0L
    private val issuedObservations = mutableMapOf<Int, Observation>()
    private fun root(displayId: Int): AccessibilityNodeInfo? = windowsOnAllDisplays[displayId]?.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }?.root
        ?: windowsOnAllDisplays[displayId]?.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }?.root

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

    suspend fun observe(includeScreen: Boolean = false, displayId: Int = 0): Observation = withContext(Dispatchers.Main) {
        val root = root(displayId)
        val nodes = mutableListOf<UiNodeSnapshot>()
        if (root != null) flatten(root, nodes, 300)
        Observation(
            packageName = root?.packageName?.toString(),
            className = root?.className?.toString(),
            nodes = nodes,
            screenshotDataUrl = if (includeScreen) captureScreenshot(displayId) else null,
        ).also { observation ->
            issuedObservations[displayId] = observation
            if (displayId == 0 && observation.packageName != null && observation.packageName != packageName) {
                AccessibilityBridge.lastExternalObservation = observation.copy(screenshotDataUrl = null)
            }
        }
    }

    private fun flatten(node: AccessibilityNodeInfo, output: MutableList<UiNodeSnapshot>, max: Int) {
        if (output.size >= max) return
        output += snapshot(node, output.size)
        for (index in 0 until node.childCount) node.getChild(index)?.let { flatten(it, output, max) }
    }

    private fun snapshot(node: AccessibilityNodeInfo, index: Int): UiNodeSnapshot {
        val rect = Rect().also(node::getBoundsInScreen)
        val className = node.className?.toString().orEmpty()
        return UiNodeSnapshot(
            index = index,
            text = if (node.isPassword) null else node.text?.toString()?.takeIf(String::isNotBlank),
            contentDescription = node.contentDescription?.toString()?.takeIf(String::isNotBlank),
            resourceId = node.viewIdResourceName,
            role = role(className, node),
            className = className,
            clickable = node.isClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            enabled = node.isEnabled,
            bounds = intArrayOf(rect.left, rect.top, rect.right, rect.bottom),
            visible = node.isVisibleToUser,
            selected = node.isSelected,
            checkable = node.isCheckable,
            checked = node.isChecked,
            focused = node.isFocused,
            password = node.isPassword,
        )
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

    private fun allNodes(displayId: Int): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || result.size >= 300) return
            result += node
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(root(displayId))
        return result
    }

    private fun actionNodes(arguments: org.json.JSONObject, displayId: Int): List<AccessibilityNodeInfo> {
        val target = arguments.optJSONObject("target") ?: org.json.JSONObject()
        val nodes = allNodes(displayId)
        val lastIssuedObservation = issuedObservations[displayId]
        val snapshots = nodes.mapIndexed { index, node -> snapshot(node, index) }
        val expectedId = arguments.optString("observation_id").takeIf(String::isNotBlank)
        if (target.has("index")) check(expectedId != null) {
            "OBSERVATION_REQUIRED: index selectors require observation_id from device_observe"
        }
        if (expectedId != null) check(nodes.firstOrNull()?.packageName?.toString() == lastIssuedObservation?.packageName) {
            "STALE_OBSERVATION: foreground app changed; observe again"
        }
        if (arguments.optString("action") in setOf("click", "long_press", "input", "scroll") && target.length() > 0) {
            UiTargetResolver.validateTargetObservation(expectedId, lastIssuedObservation?.observationId, lastIssuedObservation?.nodes, snapshots, target, arguments.getString("action"))
        } else UiTargetResolver.validateObservation(expectedId, lastIssuedObservation?.observationId, lastIssuedObservation?.nodes, snapshots)
        return nodes
    }

    /** Trusted labels for policy come from the actual target, never model-provided descriptions. */
    suspend fun approvalTarget(arguments: org.json.JSONObject, displayId: Int = 0): org.json.JSONObject = withContext(Dispatchers.Main) {
        val nodes = actionNodes(arguments, displayId)
        val snapshots = nodes.mapIndexed { index, node -> snapshot(node, index) }
        val selected = UiTargetResolver.resolve(snapshots, arguments.optJSONObject("target") ?: org.json.JSONObject(), arguments.getString("action"))
        val labels = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo, depth: Int = 0) {
            if (depth > 6 || labels.size >= 20 || !node.isVisibleToUser || node.isPassword) return
            node.text?.toString()?.takeIf(String::isNotBlank)?.let(labels::add)
            node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(labels::add)
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, depth + 1) }
        }
        var receiver: AccessibilityNodeInfo = nodes[selected.index]
        if (arguments.getString("action") == "click") {
            while (!receiver.isClickable && receiver.parent != null) receiver = receiver.parent
        }
        visit(receiver)
        org.json.JSONObject().apply {
            if (labels.isNotEmpty()) put("text", labels.distinct().joinToString(" "))
            receiver.viewIdResourceName?.let { put("resource_id", it) }
        }
    }

    suspend fun act(arguments: org.json.JSONObject, displayId: Int = 0): Observation = withContext(Dispatchers.Main) {
        val action = arguments.getString("action")
        val target = arguments.optJSONObject("target") ?: org.json.JSONObject()
        val nodes = actionNodes(arguments, displayId)
        val snapshots = nodes.mapIndexed { index, node -> snapshot(node, index) }
        fun findNode(selector: org.json.JSONObject): AccessibilityNodeInfo =
            nodes[UiTargetResolver.resolve(snapshots, selector, action).index]
        when (action) {
            "back" -> { check(displayId == 0) { "DISPLAY_KEY_REQUIRES_BROKER" }; check(performGlobalAction(GLOBAL_ACTION_BACK)) { "GLOBAL_ACTION_REJECTED: back" } }
            "home" -> { check(displayId == 0) { "DISPLAY_KEY_REQUIRES_BROKER" }; check(performGlobalAction(GLOBAL_ACTION_HOME)) { "GLOBAL_ACTION_REJECTED: home" } }
            "click" -> {
                val node = findNode(target)
                var current: AccessibilityNodeInfo? = node
                var clicked = false
                while (current != null && !clicked) {
                    clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    current = current.parent
                }
                if (!clicked) gestureAt(node, 100, displayId)
            }
            "long_press" -> gestureAt(findNode(target), arguments.optLong("duration_ms", 700), displayId)
            "input" -> {
                val node = findNode(if (target.length() == 0) org.json.JSONObject().put("role", "text_field") else target)
                check(displayId == 0 || !node.isPassword) { "USER_ACTION_REQUIRED: credential input awaits manual handoff" }
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val bundle = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, arguments.getString("value"))
                }
                check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)) { "INPUT_FAILED" }
            }
            "scroll" -> {
                val node = findNode(target.takeIf { it.length() > 0 } ?: org.json.JSONObject().put("index",
                    snapshots.singleOrNull { it.scrollable && it.enabled && it.visible }?.index
                        ?: error("SCROLL_TARGET_AMBIGUOUS: specify the visible scroll container")))
                val direction = arguments.optString("direction", "up")
                val code = if (direction == "up" || direction == "left") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                if (!node.performAction(code)) {
                    swipe(direction, arguments.optLong("duration_ms", 500), displayId)
                }
            }
            "swipe" -> swipe(arguments.optString("direction", "up"), arguments.optLong("duration_ms", 500), displayId)
            else -> error("UNSUPPORTED_ACTION: $action")
        }
        delay(700)
        observe(arguments.optBoolean("include_screen", false), displayId)
    }

    private suspend fun gestureAt(node: AccessibilityNodeInfo, duration: Long, displayId: Int) {
        val rect = Rect().also(node::getBoundsInScreen)
        val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
        dispatch(path, duration, displayId)
    }

    private suspend fun swipe(direction: String, duration: Long, displayId: Int) {
        val metrics = android.util.DisplayMetrics()
        val display = getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(displayId) ?: error("EXECUTION_DISPLAY_GONE")
        display.getRealMetrics(metrics)
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
        dispatch(path, duration, displayId)
    }

    private suspend fun dispatch(path: Path, duration: Long, displayId: Int) {
        require(duration in 1..5_000) { "DURATION_INVALID: use 1..5000 ms" }
        withTimeoutOrNull(duration + 2_000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val gesture = GestureDescription.Builder().setDisplayId(displayId).addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
                val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(Unit) }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("GESTURE_CANCELLED"))
                    }
                }, null)
                if (!accepted && continuation.isActive) continuation.resumeWithException(IllegalStateException("GESTURE_REJECTED"))
            }
        } ?: error("GESTURE_TIMEOUT")
    }

    private suspend fun captureScreenshot(displayId: Int): String? = suspendCancellableCoroutine { continuation ->
        takeScreenshot(displayId, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                if (!continuation.isActive) { result.hardwareBuffer.close(); return }
                val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                result.hardwareBuffer.close()
                if (bitmap == null) { continuation.resume(null); return }
                val scale = minOf(1f, 1080f / bitmap.width)
                val output = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
                val bytes = ByteArrayOutputStream().also { output.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
                continuation.resume("data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
            }
            override fun onFailure(errorCode: Int) { if (continuation.isActive) continuation.resume(null) }
        })
    }
}
