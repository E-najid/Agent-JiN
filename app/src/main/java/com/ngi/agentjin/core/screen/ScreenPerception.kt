package com.ngi.agentjin.core.screen

import android.graphics.Rect

class ScreenPerception {
    fun isAvailable(): Boolean = JinAccessibilityService.isConnected()

    fun dumpText(maxNodes: Int = 400): String {
        val svc = JinAccessibilityService.instance
            ?: return "ACCESSIBILITY_SERVICE_NOT_CONNECTED"
        val nodes = svc.dumpTree(maxNodes)
        if (nodes.isEmpty()) return "EMPTY_TREE"
        return buildString {
            appendLine("package=${nodes.firstOrNull()?.packageName ?: "?"}")
            nodes.forEachIndexed { i, n ->
                append("[$i]")
                if (!n.id.isNullOrBlank()) append(" id=${n.id}")
                if (!n.cls.isNullOrBlank()) append(" class=${shortClass(n.cls)}")
                if (!n.text.isNullOrBlank()) append(" text=\"${n.text.take(80)}\"")
                if (!n.desc.isNullOrBlank()) append(" desc=\"${n.desc.take(80)}\"")
                val flags = mutableListOf<String>()
                if (n.clickable) flags += "click"
                if (n.editable) flags += "edit"
                if (n.scrollable) flags += "scroll"
                if (flags.isNotEmpty()) append(" ${flags.joinToString(",")}")
                append(" bounds=${fmt(n.bounds)}")
                appendLine()
            }
        }
    }

    fun treeInsufficient(dump: String): Boolean {
        if (dump == "ACCESSIBILITY_SERVICE_NOT_CONNECTED") return true
        if (dump == "EMPTY_TREE") return true
        val lines = dump.lineSequence().filter { it.startsWith("[") }.count()
        return lines < 3
    }

    private fun shortClass(cls: String): String = cls.substringAfterLast('.')
    private fun fmt(r: Rect) = "[${r.left},${r.top},${r.right},${r.bottom}]"
}
