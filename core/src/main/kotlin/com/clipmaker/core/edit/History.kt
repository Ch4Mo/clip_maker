package com.clipmaker.core.edit

/**
 * Snapshot-based undo/redo. The model is immutable so snapshots share almost all of their
 * structure and are cheap to keep.
 */
class History<T>(initial: T, private val limit: Int = 200) {
    private val undoStack = ArrayDeque<Entry<T>>()
    private val redoStack = ArrayDeque<Entry<T>>()

    var current: T = initial
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Label of the action that [undo] would revert. */
    val undoLabel: String? get() = undoStack.lastOrNull()?.label
    val redoLabel: String? get() = redoStack.lastOrNull()?.label

    private var coalesceKey: String? = null

    /**
     * Records a new state. Successive pushes sharing the same non-null [coalesce] key (e.g. while
     * dragging a slider) are merged into a single undo step.
     */
    fun push(state: T, label: String = "", coalesce: String? = null) {
        if (state == current) return
        if (coalesce != null && coalesce == coalesceKey && undoStack.isNotEmpty()) {
            current = state
            redoStack.clear()
            return
        }
        undoStack.addLast(Entry(current, label))
        if (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
        current = state
        coalesceKey = coalesce
    }

    /** Ends the current coalescing group, so the next push creates a new undo step. */
    fun commit() {
        coalesceKey = null
    }

    fun undo(): T? {
        val entry = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(Entry(current, entry.label))
        current = entry.state
        coalesceKey = null
        return current
    }

    fun redo(): T? {
        val entry = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(Entry(current, entry.label))
        current = entry.state
        coalesceKey = null
        return current
    }

    /** Replaces the current state without recording history (e.g. metadata refresh). */
    fun replace(state: T) {
        current = state
    }

    private data class Entry<T>(val state: T, val label: String)
}
