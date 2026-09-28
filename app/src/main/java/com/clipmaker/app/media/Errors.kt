package com.clipmaker.app.media

import android.util.Log

private const val TAG = "ClipMaker"

/**
 * Logs [error] with its full stack trace and returns a one-line description naming the root cause,
 * so on-screen messages say *why* Media3 failed rather than only its error code.
 */
fun reportError(context: String, error: Throwable): String {
    Log.e(TAG, context, error)
    var root = error
    while (root.cause != null && root.cause !== root) root = root.cause!!
    val head = (error as? androidx.media3.common.PlaybackException)?.errorCodeName
        ?: (error as? androidx.media3.transformer.ExportException)?.errorCodeName
        ?: error.message
        ?: error.javaClass.simpleName
    if (root === error) return head
    return "$head — ${root.javaClass.simpleName}: ${root.message ?: ""}".trimEnd(' ', ':')
}
