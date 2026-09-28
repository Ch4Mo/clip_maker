package com.clipmaker.app.editor

import com.clipmaker.app.data.ProjectRepository
import com.clipmaker.core.edit.History
import com.clipmaker.core.model.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

data class HistoryState(
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoLabel: String? = null,
    val redoLabel: String? = null,
)

/**
 * Live editing state of one open project, shared by the editor, camera, recorder and export
 * screens. Every edit goes through [edit] so it is undoable and autosaved.
 */
@OptIn(FlowPreview::class)
class ProjectSession(
    initial: Project,
    private val repository: ProjectRepository,
    scope: CoroutineScope,
) {
    private val history = History(initial)
    private val _project = MutableStateFlow(initial)
    val project: StateFlow<Project> = _project.asStateFlow()

    private val _history = MutableStateFlow(HistoryState())
    val historyState: StateFlow<HistoryState> = _history.asStateFlow()

    /** Current playhead position on the timeline. */
    val playheadUs = MutableStateFlow(0L)

    /** Selected clip ids. */
    val selection = MutableStateFlow<Set<String>>(emptySet())

    init {
        scope.launch {
            _project.drop(1).debounce(700).collect { repository.save(it.copy(updatedAtMs = System.currentTimeMillis())) }
        }
    }

    val current: Project get() = _project.value

    /**
     * Applies an undoable edit. Calls sharing a [coalesce] key are merged into one undo step until
     * [commit] is called (e.g. while dragging).
     */
    fun edit(label: String, coalesce: String? = null, transform: (Project) -> Project) {
        val next = transform(_project.value)
        if (next == _project.value) return
        history.push(next, label, coalesce)
        publish()
    }

    fun commit() = history.commit()

    fun undo() {
        history.undo() ?: return
        publish()
    }

    fun redo() {
        history.redo() ?: return
        publish()
    }

    private fun publish() {
        _project.value = history.current
        _history.value = HistoryState(history.canUndo, history.canRedo, history.undoLabel, history.redoLabel)
        val existing = history.current.tracks.flatMap { t -> t.clips.map { it.id } }.toSet()
        selection.value = selection.value.intersect(existing)
    }

    suspend fun saveNow() = repository.save(_project.value.copy(updatedAtMs = System.currentTimeMillis()))
}
