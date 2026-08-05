package com.torchlight.auto

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutoAccessibilityService : AccessibilityService() {
    companion object {
        const val ACTION_TEXT = "com.torchlight.auto.ACCESSIBILITY_TEXT"
        val KEYWORDS = listOf("破空", "传奇", "稀有", "史诗", "通货", "装备", "掉落", "获得", "拾取", "Item", "Legendary")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 方法1：从事件本身取文字
        val eventText = event.text?.joinToString(" ") ?: ""
        if (eventText.isNotEmpty()) checkAndSend(eventText)

        // 方法2：遍历当前窗口所有节点（更彻底但可能卡）
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            try {
                rootInActiveWindow?.let { root ->
                    traverseNode(root)
                    root.recycle()
                }
            } catch (e: Exception) {
                Log.e("A11y", "traverse error", e)
            }
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo) {
        val text = node.text?.toString() ?: ""
        if (text.isNotEmpty()) checkAndSend(text)

        for (i in 0 until node.childCount) {
            try {
                node.getChild(i)?.let {
                    traverseNode(it)
                    it.recycle()
                }
            } catch (_: Exception) {}
        }
    }

    private fun checkAndSend(text: String) {
        if (KEYWORDS.any { text.contains(it) }) {
            Log.d("A11y", "捕获: $text")
            sendBroadcast(Intent(ACTION_TEXT).putExtra("text", text))
        }
    }

    override fun onInterrupt() {}
}
