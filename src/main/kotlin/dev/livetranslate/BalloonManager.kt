package dev.livetranslate

import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.ChatFormatting

object BalloonManager {
    val activeBalloons = ConcurrentHashMap<String, LinkedList<Balloon>>()
    private const val BALLOON_DURATION_MS = 8000L
    private const val MAX_STACK_SIZE = 8
    private const val MAX_LINE_LENGTH = 40  // max chars per balloon line

    fun addBalloon(nickname: String, message: String, color: String, durationMs: Long) {
        val lines = wrapText(message, MAX_LINE_LENGTH)
        val playerStack = activeBalloons.computeIfAbsent(nickname) { LinkedList() }
        synchronized(playerStack) {
            // Add each wrapped line as a separate balloon (bottom line first)
            for (line in lines.reversed()) {
                playerStack.addFirst(Balloon(line, color, durationMs))
            }
            // Trim stack if too many balloons
            while (playerStack.size > MAX_STACK_SIZE) {
                playerStack.removeLast()
            }
        }
    }

    fun getBalloonsForNameTag(nameTag: String): LinkedList<Balloon>? {
        val cleanNameTag = ChatFormatting.stripFormatting(nameTag)?.trim() ?: return null

        // Try exact match first
        val exactMatch = activeBalloons[cleanNameTag]
        if (exactMatch != null) {
            return exactMatch
        }

        // Try matching clean name tag with registered nicknames
        for ((nick, stack) in activeBalloons) {
            // Matches if nameTag ends with the player nick, e.g. "[VIP] Nick" or "Prefix Nick"
            if (cleanNameTag.endsWith(nick) || cleanNameTag.contains(nick)) {
                return stack
            }
        }
        return null
    }

    fun tick() {
        for (stack in activeBalloons.values) {
            synchronized(stack) {
                stack.removeIf { it.isExpired() }
            }
        }
        activeBalloons.entries.removeIf { entry ->
            synchronized(entry.value) {
                entry.value.isEmpty()
            }
        }
    }

    /**
     * Wraps text into lines of at most [maxLen] characters,
     * breaking at spaces when possible.
     */
    private fun wrapText(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)

        val lines = mutableListOf<String>()
        var remaining = text.trim()

        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxLen) {
                lines.add(remaining)
                break
            }
            // Find the last space within maxLen
            var breakAt = remaining.lastIndexOf(' ', maxLen)
            if (breakAt <= 0) {
                // No space found — hard break
                breakAt = maxLen
            }
            lines.add(remaining.substring(0, breakAt).trimEnd())
            remaining = remaining.substring(breakAt).trimStart()
        }
        return lines
    }

    /** Clears all speech balloons currently rendered on the screen. */
    fun clearAll() {
        activeBalloons.clear()
    }

    class Balloon(val message: String, val color: String, durationMs: Long) {
        private val expiryTime = System.currentTimeMillis() + durationMs

        fun isExpired(): Boolean {
            return System.currentTimeMillis() > expiryTime
        }
    }
}
