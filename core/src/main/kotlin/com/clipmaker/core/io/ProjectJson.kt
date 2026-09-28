package com.clipmaker.core.io

import com.clipmaker.core.model.Project
import kotlinx.serialization.json.Json

object ProjectJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
        coerceInputValues = true
    }

    fun encode(project: Project): String = json.encodeToString(Project.serializer(), project)

    fun decode(text: String): Project = migrate(json.decodeFromString(Project.serializer(), text))

    /** Hook for future format migrations. */
    private fun migrate(project: Project): Project =
        if (project.formatVersion < Project.CURRENT_FORMAT_VERSION) project.copy(formatVersion = Project.CURRENT_FORMAT_VERSION)
        else project
}
