package com.clipmaker.app

import android.content.Context
import com.clipmaker.app.data.ProjectRepository
import com.clipmaker.app.data.SoundLibrary
import com.clipmaker.app.editor.ProjectSession
import com.clipmaker.app.media.AudioDecoder
import com.clipmaker.app.media.Exporter
import com.clipmaker.app.media.MediaProbe
import com.clipmaker.app.media.ThumbnailRepository
import com.clipmaker.app.media.WaveformRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Manual dependency container (kept simple on purpose: no DI framework needed). */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val projects = ProjectRepository(appContext)
    val probe = MediaProbe(appContext)
    val decoder = AudioDecoder(appContext)
    val waveforms = WaveformRepository(appContext, decoder)
    val thumbnails = ThumbnailRepository(appContext)
    val sounds = SoundLibrary(appContext)
    val exporter = Exporter(appContext)

    private val sessions = HashMap<String, ProjectSession>()
    private val sessionMutex = Mutex()

    suspend fun session(projectId: String): ProjectSession? = sessionMutex.withLock {
        sessions[projectId] ?: projects.load(projectId)?.let { ProjectSession(it, projects, scope).also { s -> sessions[projectId] = s } }
    }

    fun closeSession(projectId: String) {
        sessions.remove(projectId)
    }
}
