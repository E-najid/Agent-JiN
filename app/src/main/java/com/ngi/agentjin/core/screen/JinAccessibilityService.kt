package com.ngi.agentjin.core.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class UiNode(
    val id: String?,
    val cls: String?,
    val text: String?,
    val desc: String?,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val bounds: Rect,
    val packageName: String?,
)

class JinAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun dumpTree(maxNodes: Int = 400): List<UiNode> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<UiNode>(maxNodes)
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        while (q.isNotEmpty() && out.size < maxNodes) {
            val n = q.removeFirst()
            val bounds = Rect()
            n.getBoundsInScreen(bounds)
            val text = n.text?.toString()
            val desc = n.contentDescription?.toString()
            val useful = n.isClickable || n.isEditable || n.isScrollable || !text.isNullOrBlank() || !desc.isNullOrBlank()
            if (useful) {
                out += UiNode(
                    id = n.viewIdResourceName,
                    cls = n.className?.toString(),
                    text = text,
                    desc = desc,
                    clickable = n.isClickable,
                    longClickable = n.isLongClickable,
                    editable = n.isEditable,
                    scrollable = n.isScrollable,
                    enabled = n.isEnabled,
                    bounds = bounds,
                    packageName = n.packageName?.toString(),
                )
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { q.add(it) }
            }
        }
        return out
    }

    fun findByText(query: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val q = query.trim()
        if (q.isEmpty()) return null
        root.findAccessibilityNodeInfosByText(q)?.firstOrNull()?.let { return it }
        return findFirst(root) { n ->
            val t = n.text?.toString().orEmpty()
            val d = n.contentDescription?.toString().orEmpty()
            t.contains(q, ignoreCase = true) || d.contains(q, ignoreCase = true)
        }
    }

    fun findByViewId(id: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
    }

    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        while (cur != null) {
            if (cur.isClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            cur = cur.parent
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        return tap(r.centerX().toFloat(), r.centerY().toFloat())
    }

    fun longClickNode(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        while (cur != null) {
            if (cur.isLongClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            cur = cur.parent
        }
        return false
    }

    fun typeInto(node: AccessibilityNodeInfo, text: String): Boolean {
        var target: AccessibilityNodeInfo? = node
        if (target?.isEditable != true) {
            target = findFirst(rootInActiveWindow) { it.isEditable && it.isFocused }
                ?: findFirst(rootInActiveWindow) { it.isEditable }
        }
        if (target == null) return false
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scroll(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = when (direction.lowercase()) {
            "up", "backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        val node = findFirst(root) { it.isScrollable } ?: return false
        return node.performAction(action)
    }

    fun global(action: Int): Boolean = performGlobalAction(action)

    fun tap(x: Float, y: Float, durationMs: Long = 60L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    suspend fun takeScreenshotRgb(): Triple<ByteArray, Int, Int>? {
        if (Build.VERSION.SDK_INT < 30) return null
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hw = screenshot.hardwareBuffer
                            val colorSpace = screenshot.colorSpace
                            val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(hw, colorSpace)
                                ?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            hw.close()
                            screenshot.release()
                            if (bitmap == null) {
                                cont.resume(null)
                                return
                            }
                            val w = bitmap.width
                            val h = bitmap.height
                            val pixels = IntArray(w * h)
                            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                            bitmap.recycle()
                            val rgb = ByteArray(w * h * 3)
                            var o = 0
                            for (p in pixels) {
                                rgb[o++] = ((p shr 16) and 0xFF).toByte()
                                rgb[o++] = ((p shr 8) and 0xFF).toByte()
                                rgb[o++] = (p and 0xFF).toByte()
                            }
                            cont.resume(Triple(rgb, w, h))
                        } catch (t: Throwable) {
                            cont.resume(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        cont.resume(null)
                    }
                },
            )
        }
    }

    private fun findFirst(root: AccessibilityNodeInfo?, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            if (pred(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    companion object {
        @Volatile
        var instance: JinAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }
}
