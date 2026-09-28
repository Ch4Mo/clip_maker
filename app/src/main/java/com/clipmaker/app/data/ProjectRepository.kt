package com.clipmaker.app.data

import android.content.Context
import com.clipmaker.core.io.ProjectJson
import com.clipmaker.core.model.MediaKind
import com.clipmaker.core.model.Project
import com.clipmaker.core.model.ProjectSettings
import com.clipmaker.core.util.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class ProjectSummary(
    val id: String,
    val name: String,
    val updatedAtMs: Long,
    val durationUs: Long,
    val aspectLabel: String,
    val aspectValue: Float,
    /** First visual asset, used as cover image. */
    val coverUri: String?,
    val clipCount: Int,
)

/** Stores each project as JSON in its own folder, next to the media it owns (recordings...). */
class ProjectRepository(private val context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }
    private val mutex = Mutex()
    private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val projects: StateFlow<List<ProjectSummary>> = _projects.asStateFlow()

    fun projectDir(id: String) = File(root, id).apply { mkdirs() }

    /** Folder for media produced inside the app (camera, recordings, extracted audio...). */
    fun mediaDir(id: String) = File(projectDir(id), "media").apply { mkdirs() }

    private fun file(id: String) = File(projectDir(id), "project.json")

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val list = root.listFiles()?.mapNotNull { dir ->
            runCatching { summarize(ProjectJson.decode(File(dir, "project.json").readText())) }.getOrNull()
        }?.sortedByDescending { it.updatedAtMs } ?: emptyList()
        _projects.value = list
    }

    suspend fun create(name: String, settings: ProjectSettings): Project {
        val now = System.currentTimeMillis()
        val project = Project.create(Ids.project(), name.ifBlank { "Nouveau clip" }, now, settings)
        save(project)
        return project
    }

    suspend fun load(id: String): Project? = withContext(Dispatchers.IO) {
        mutex.withLock { runCatching { ProjectJson.decode(file(id).readText()) }.getOrNull() }
    }

    suspend fun save(project: Project) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val target = file(project.id)
            val tmp = File(target.parentFile, "project.json.tmp")
            tmp.writeText(ProjectJson.encode(project))
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
        _projects.value = (listOf(summarize(project)) + _projects.value.filterNot { it.id == project.id })
            .sortedByDescending { it.updatedAtMs }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock { File(root, id).deleteRecursively() }
        _projects.value = _projects.value.filterNot { it.id == id }
    }

    suspend fun duplicate(id: String): Project? {
        val original = load(id) ?: return null
        val now = System.currentTimeMillis()
        val copy = original.copy(id = Ids.project(), name = original.name + " (copie)", createdAtMs = now, updatedAtMs = now)
        withContext(Dispatchers.IO) {
            // Media recorded inside the original project is shared by absolute path, which stays valid.
            projectDir(copy.id)
        }
        save(copy)
        return copy
    }

    suspend fun rename(id: String, name: String) {
        val p = load(id) ?: return
        save(p.copy(name = name, updatedAtMs = System.currentTimeMillis()))
    }

    private fun summarize(p: Project): ProjectSummary {
        val cover = p.mainVideoTrack?.sortedClips?.firstNotNullOfOrNull { c ->
            p.asset(c.assetId)?.takeIf { it.kind != MediaKind.AUDIO }
        } ?: p.assets.firstOrNull { it.kind != MediaKind.AUDIO }
        return ProjectSummary(
            id = p.id,
            name = p.name,
            updatedAtMs = p.updatedAtMs,
            durationUs = p.durationUs,
            aspectLabel = p.settings.aspectRatio.label,
            aspectValue = p.settings.aspectRatio.value,
            coverUri = cover?.uri,
            clipCount = p.tracks.sumOf { it.clips.size },
        )
    }
}
