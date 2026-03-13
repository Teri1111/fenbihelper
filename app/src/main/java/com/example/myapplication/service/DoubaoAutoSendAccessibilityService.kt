package com.example.myapplication.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class DoubaoAutoSendAccessibilityService : AccessibilityService() {

    private val logTag = "DoubaoAutoSendA11y"

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (!supportedPackages.contains(packageName)) {
            return
        }
        if (!DoubaoAutoSendCoordinator.isPending()) {
            return
        }

        val root = rootInActiveWindow ?: return
        if (!isSendUiReady(root)) {
            return
        }
        if (!DoubaoAutoSendCoordinator.shouldAttemptNow()) {
            return
        }

        val clicked = clickByViewId(root) || clickByDescriptionOrText(root) || clickByBounds(root)
        DoubaoAutoSendCoordinator.recordAttempt(clicked)
        if (clicked) {
            val stillVisible = hasSendButton(rootInActiveWindow)
            Log.d(logTag, "Auto-clicked Doubao send button. stillVisible=$stillVisible")
            if (!stillVisible) {
                DoubaoAutoSendCoordinator.clear()
            }
        }
    }

    override fun onInterrupt() {
        // No-op.
    }

    private fun clickByViewId(root: AccessibilityNodeInfo): Boolean {
        val sendNodes = root.findAccessibilityNodeInfosByViewId(doubaoSendViewId)
        return sendNodes.firstOrNull { it.isVisibleToUser }?.let { clickNodeOrParent(it) } ?: false
    }

    private fun clickByDescriptionOrText(root: AccessibilityNodeInfo): Boolean {
        val descNodes = root.findAccessibilityNodeInfosByText(sendText)
        return descNodes.firstOrNull { node ->
            val desc = node.contentDescription?.toString() ?: ""
            node.isVisibleToUser && (desc.contains(sendText) || (node.text?.toString() ?: "").contains(sendText))
        }?.let { clickNodeOrParent(it) } ?: false
    }

    private fun clickByBounds(root: AccessibilityNodeInfo): Boolean {
        val targetBounds = Rect(1091, 1491, 1233, 1672)
        return findNodeByBounds(root, targetBounds)?.let { clickNodeOrParent(it) } ?: false
    }

    private fun isSendUiReady(root: AccessibilityNodeInfo): Boolean {
        if (hasFocusedEditable(root)) {
            return true
        }

        val sendNode = findPrimarySendNode(root) ?: return false
        val bounds = Rect()
        sendNode.getBoundsInScreen(bounds)
        val screenHeight = resources.displayMetrics.heightPixels
        val keyboardLikelyVisible = bounds.bottom < (screenHeight * KEYBOARD_RAISED_RATIO)
        return keyboardLikelyVisible && sendNode.isEnabled && sendNode.isVisibleToUser
    }

    private fun hasFocusedEditable(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString() ?: ""
        if (node.isFocused && className.contains("EditText")) {
            return true
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (hasFocusedEditable(child)) {
                return true
            }
        }
        return false
    }

    private fun findPrimarySendNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByViewId(doubaoSendViewId)
            .firstOrNull { it.isVisibleToUser }
            ?.let { return it }

        return root.findAccessibilityNodeInfosByText(sendText)
            .firstOrNull { node ->
                val desc = node.contentDescription?.toString() ?: ""
                node.isVisibleToUser && (desc.contains(sendText) || (node.text?.toString() ?: "").contains(sendText))
            }
    }

    private fun hasSendButton(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false
        return findPrimarySendNode(root) != null
    }

    private fun findNodeByBounds(node: AccessibilityNodeInfo, target: Rect): AccessibilityNodeInfo? {
        val currentBounds = Rect()
        node.getBoundsInScreen(currentBounds)
        if (Rect.intersects(currentBounds, target) && node.isVisibleToUser) {
            return node
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val matched = findNodeByBounds(child, target)
            if (matched != null) {
                return matched
            }
        }
        return null
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            val currentNode = current ?: return false
            if (currentNode.isClickable && currentNode.isEnabled) {
                return currentNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = currentNode.parent
        }
        return false
    }

    companion object {
        private const val KEYBOARD_RAISED_RATIO = 0.86f
        private val supportedPackages = setOf(
            "com.larus.nova",
            "com.bytedance.doubao",
        )
        private const val doubaoSendViewId = "com.larus.nova:id/action_send"
        private const val sendText = "发送"
    }
}
