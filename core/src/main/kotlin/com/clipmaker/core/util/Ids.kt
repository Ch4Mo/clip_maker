package com.clipmaker.core.util

import java.util.UUID

object Ids {
    /** Replaceable for deterministic tests. */
    @Volatile
    var generator: (prefix: String) -> String = { prefix -> "$prefix-${UUID.randomUUID().toString().substring(0, 8)}" }

    fun clip(): String = generator("clip")
    fun track(): String = generator("track")
    fun asset(): String = generator("asset")
    fun project(): String = generator("project")
    fun marker(): String = generator("marker")
}

object TimeFormat {
    /** Formats as `m:ss.cc` (or `h:mm:ss.cc` for long durations). */
    fun short(us: Long): String {
        val totalCs = (us.coerceAtLeast(0) / 10_000)
        val cs = totalCs % 100
        val totalS = totalCs / 100
        val s = totalS % 60
        val m = (totalS / 60) % 60
        val h = totalS / 3600
        return if (h > 0) "%d:%02d:%02d.%02d".format(h, m, s, cs) else "%d:%02d.%02d".format(m, s, cs)
    }

    /** SMPTE-like timecode `hh:mm:ss:ff`. */
    fun timecode(us: Long, fps: Int): String {
        val totalFrames = us.coerceAtLeast(0) * fps / 1_000_000
        val f = totalFrames % fps
        val totalS = totalFrames / fps
        return "%02d:%02d:%02d:%02d".format(totalS / 3600, (totalS / 60) % 60, totalS % 60, f)
    }
}
