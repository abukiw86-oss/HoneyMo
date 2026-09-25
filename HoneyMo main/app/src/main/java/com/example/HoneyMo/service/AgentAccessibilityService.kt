package com.example.HoneyMo.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.HoneyMo.agent.AgentCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.delay

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AgentAccessibility"
        var instance: AgentAccessibilityService? = null
            private set
    }

    private val _currentPackage = MutableStateFlow<String>("")
    val currentPackage = _currentPackage.asStateFlow()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AgentAccessibilityService connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance == this) {
            instance = null
        }
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: ""
            _currentPackage.value = pkg
            Log.d(TAG, "Window state changed, current package: \$pkg")
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "AgentAccessibilityService interrupted")
    }

    suspend fun executeCommand(command: AgentCommand): Boolean {
        Log.d(TAG, "Executing command: \${command.action}")
        var success = false
        try {
            when (command.action) {
                "tap_node" -> {
                    val textMatch = command.parameters.optString("textMatch")
                    val viewId = command.parameters.optString("viewId")
                    val root = rootInActiveWindow
                    var targetNode: AccessibilityNodeInfo? = null

                    if (textMatch.isNotEmpty()) {
                        val nodes = root?.findAccessibilityNodeInfosByText(textMatch)
                        if (!nodes.isNullOrEmpty()) {
                            targetNode = nodes[0]
                        }
                    }
                    if (targetNode == null && viewId.isNotEmpty()) {
                        val nodes = root?.findAccessibilityNodeInfosByViewId(viewId)
                        if (!nodes.isNullOrEmpty()) {
                            targetNode = nodes[0]
                        }
                    }

                    if (targetNode != null) {
                        val rect = Rect()
                        targetNode.getBoundsInScreen(rect)
                        dispatchTap(rect.centerX().toFloat(), rect.centerY().toFloat())
                        success = true
                    } else {
                        val fallbackX = command.parameters.optDouble("fallbackX", -1.0)
                        val fallbackY = command.parameters.optDouble("fallbackY", -1.0)
                        if (fallbackX >= 0.0 && fallbackY >= 0.0) {
                            val metrics = resources.displayMetrics
                            val x = fallbackX.toFloat() * metrics.widthPixels
                            val y = fallbackY.toFloat() * metrics.heightPixels
                            dispatchTap(x, y)
                            success = true
                        }
                    }
                }
                "type_text" -> {
                    val text = command.parameters.optString("text")
                    val focusedNode = findFocusedNode(rootInActiveWindow)
                    if (focusedNode != null) {
                        val arguments = Bundle()
                        arguments.putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text
                        )
                        success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    }
                    if (!success) {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Agent Text", text)
                        clipboard.setPrimaryClip(clip)
                        success = focusedNode?.performAction(AccessibilityNodeInfo.ACTION_PASTE) ?: false
                    }
                }
                "launch_app" -> {
                    val packageName = command.parameters.optString("packageName")
                    val intent = packageManager.getLaunchIntentForPackage(packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        success = true
                    }
                }
                "scroll" -> {
                    val direction = command.parameters.optString("direction")
                    val scrollableNode = findScrollableNode(rootInActiveWindow)
                    if (scrollableNode != null) {
                        if (direction == "forward" || direction == "down") {
                            success = scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        } else {
                            success = scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                        }
                    }
                    if (!success) {
                        val metrics = resources.displayMetrics
                        val centerX = metrics.widthPixels / 2f
                        val centerY = metrics.heightPixels / 2f
                        val path = Path()
                        path.moveTo(centerX, centerY)
                        if (direction == "forward" || direction == "down") {
                            path.lineTo(centerX, centerY - metrics.heightPixels * 0.3f)
                        } else {
                            path.lineTo(centerX, centerY + metrics.heightPixels * 0.3f)
                        }
                        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
                        val gesture = GestureDescription.Builder().addStroke(stroke).build()
                        success = dispatchGesture(gesture, null, null)
                    }
                }
                "press_back" -> {
                    success = performGlobalAction(GLOBAL_ACTION_BACK)
                }
                "press_home" -> {
                    success = performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }
            if (success) {
                delay(300)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command", e)
        }
        return success
    }

    private fun findFocusedNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isFocused) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            val focused = findFocusedNode(child)
            if (focused != null) return focused
        }
        return null
    }

    private fun findScrollableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isScrollable) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            val scrollable = findScrollableNode(child)
            if (scrollable != null) return scrollable
        }
        return null
    }

    private fun dispatchTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    fun getAccessibilityTree(): String {
        val root = rootInActiveWindow ?: return "{}"
        val result = traverseNode(root, 0, mutableListOf())
        return result.toString()
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, depth: Int, nodeCount: MutableList<Int>): JSONObject {
        val json = JSONObject()
        if (node == null || depth > 5 || nodeCount.size > 100) return json
        nodeCount.add(1)

        json.put("text", node.text?.toString() ?: "")
        json.put("viewId", node.viewIdResourceName ?: "")
        json.put("className", node.className?.toString() ?: "")
        json.put("clickable", node.isClickable)
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val bounds = JSONObject().apply {
            put("left", rect.left)
            put("top", rect.top)
            put("right", rect.right)
            put("bottom", rect.bottom)
        }
        json.put("bounds", bounds)

        val children = JSONArray()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val childJson = traverseNode(child, depth + 1, nodeCount)
            if (childJson.length() > 0) {
                children.put(childJson)
            }
        }
        json.put("children", children)
        return json
    }
}
