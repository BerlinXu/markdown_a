package com.qoder.mdreader.core

/** Stores only changed ranges; both stacks share one character budget. */
class EditHistory(private val budget: Int = 1024 * 1024) {
    private data class Change(val at: Int, val removed: String, val inserted: String, val beforeCursor: Int, val afterCursor: Int) {
        val cost get() = removed.length + inserted.length
    }
    private val undo = java.util.ArrayDeque<Change>()
    private val redo = java.util.ArrayDeque<Change>()
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    fun record(before: String, after: String, cursor: Int, afterCursor: Int) {
        if (before == after) return
        var first = 0
        while (first < minOf(before.length, after.length) && before[first] == after[first]) first++
        var tail = 0
        while (tail < minOf(before.length, after.length) - first && before[before.lastIndex - tail] == after[after.lastIndex - tail]) tail++
        val change = Change(first, before.substring(first, before.length - tail), after.substring(first, after.length - tail), cursor, afterCursor)
        redo.clear()
        undo.addLast(change)
        var size = undo.sumOf { it.cost }
        while (size > budget && undo.isNotEmpty()) size -= undo.removeFirst().cost
    }
    fun undo(text: String): Pair<String, Int>? {
        val change = undo.pollLast() ?: return null
        if (change.at > text.length || text.substring(change.at, (change.at + change.inserted.length).coerceAtMost(text.length)) != change.inserted) { undo.clear(); redo.clear(); return null }
        redo.addLast(change)
        return text.replaceRange(change.at, change.at + change.inserted.length, change.removed) to change.beforeCursor
    }
    fun redo(text: String): Pair<String, Int>? {
        val change = redo.pollLast() ?: return null
        if (change.at > text.length || text.substring(change.at, (change.at + change.removed.length).coerceAtMost(text.length)) != change.removed) { undo.clear(); redo.clear(); return null }
        undo.addLast(change)
        return text.replaceRange(change.at, change.at + change.removed.length, change.inserted) to change.afterCursor
    }
}
