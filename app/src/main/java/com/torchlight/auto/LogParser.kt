package com.torchlight.auto

object LogParser {
    fun convertFromLogStructure(logText: String): Map<String, Any> {
        val lines = logText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val stack = mutableListOf<MutableMap<String, Any>>()
        val root = mutableMapOf<String, Any>()
        for (line in lines) {
            val level = line.count { it == '|' }
            val content = line.replace(Regex("""\|+"""), "").trim()
            while (stack.size > level) stack.removeAt(stack.lastIndex)
            val parent = if (stack.isEmpty()) root else stack.last()
            if (parent !is MutableMap<*, *>) continue
            @Suppress("UNCHECKED_CAST")
            val parentMap = parent as MutableMap<String, Any>
            if ('[' in content && ']' in content) {
                val keyPart = content.substring(0, content.indexOf('[')).trim()
                val valuePart = content.substring(content.indexOf('[') + 1, content.lastIndexOf(']')).trim()
                val value: Any = when {
                    valuePart.equals("true", ignoreCase = true) -> true
                    valuePart.equals("false", ignoreCase = true) -> false
                    valuePart.matches(Regex("""^-?\d+$""")) -> valuePart.toInt()
                    else -> valuePart
                }
                val keys = keyPart.split("+").map { it.trim() }.filter { it.isNotEmpty() }
                var current = parentMap
                for (i in keys.indices) {
                    val key = keys[i]
                    if (i == keys.lastIndex) current[key] = value
                    else {
                        if (current[key] !is MutableMap<*, *>) current[key] = mutableMapOf<String, Any>()
                        @Suppress("UNCHECKED_CAST")
                        current = current[key] as MutableMap<String, Any>
                    }
                }
                @Suppress("UNCHECKED_CAST")
                val lastNode = current as? MutableMap<String, Any>
                if (lastNode != null) stack.add(lastNode)
            } else {
                val keys = content.split("+").map { it.trim() }.filter { it.isNotEmpty() }
                var current = parentMap
                for (key in keys) {
                    if (current[key] !is MutableMap<*, *>) current[key] = mutableMapOf<String, Any>()
                    @Suppress("UNCHECKED_CAST")
                    current = current[key] as MutableMap<String, Any>
                }
                stack.add(current)
            }
        }
        return root
    }

    fun scanDropBlocks(text: String): List<String> {
        val lines = text.split("\n")
        val blocks = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (Regex("""\+DropItems\+1\+""").containsMatchIn(line)) {
                val currentBlock = mutableListOf(line)
                var j = i + 1
                while (j < lines.size) {
                    val currentLine = lines[j]
                    if ("Display:" in currentLine) { currentBlock.add(currentLine); j++; break }
                    currentBlock.add(currentLine); j++
                }
                blocks.add(currentBlock.joinToString("\n"))
                i = j
            } else i++
        }
        return blocks
    }
}
