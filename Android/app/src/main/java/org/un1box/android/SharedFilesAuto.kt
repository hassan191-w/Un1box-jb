package org.un1box.android

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Keeps the shared folder in step by itself while a device is running: a sync when the device comes up, another a few
 * seconds after the phone's folder stops changing, and another when the iOS folder is found changed (looked at every
 * fifteen seconds). A failure, such as the guest still starting, is tried again a little later. It lives in the app's
 * process, started with it, so it works while the app is open or has only just been left.
 */
internal object SharedFilesAuto {
    private const val PREFS = "shared_files"
    private const val KEY = "auto"
    private var started = false

    /** What the screen shows about the last sync, by hand or by itself. */
    var status by mutableStateOf("")

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        Thread({ watch(app) }, "shared-files-auto").apply { isDaemon = true; start() }
    }

    private fun watch(app: Context) {
        var socket = ""
        var synced = false
        var local = "" // the phone's folder after the last sync
        var remote = "" // the iOS folder after the last sync
        var seenLocal = "" // the phone's folder at the last look, to wait until a copy has finished
        var retryAt = 0L
        var failures = 0
        var tick = 0
        var serviceOn = false
        while (true) {
            try {
                Thread.sleep(3000)
            } catch (_: InterruptedException) {
                return
            }
            val current = if (enabled(app)) StoreInstaller.of(app).socket.orEmpty() else ""
            if (current.isEmpty()) {
                if (serviceOn) {
                    SharedFilesService.stop(app)
                    serviceOn = false
                }
                socket = ""
                synced = false
                continue
            }
            if (!serviceOn) {
                // While a device runs, a notification keeps this process, and so the sync, alive in the background.
                serviceOn = SharedFilesService.start(app)
            }
            if (current != socket) {
                socket = current
                synced = false
                failures = 0
                retryAt = 0L
            }
            if (System.currentTimeMillis() < retryAt) continue
            tick++
            try {
                var due = !synced
                val nowLocal = SharedFilesSync.localSignature(app)
                if (synced && nowLocal != local && nowLocal == seenLocal) due = true
                seenLocal = nowLocal
                if (!due && tick % 5 == 0 && SharedFilesSync.peek(app, socket) != remote) due = true
                if (due) {
                    val outcome = SharedFilesSync.run(app, socket)
                    local = SharedFilesSync.localSignature(app)
                    remote = outcome.remoteSignature
                    synced = true
                    failures = 0
                    status = "Automatic sync: ${outcome.message}"
                }
            } catch (e: Exception) {
                failures++
                status = "Automatic sync is waiting for the device (${e.message})"
                retryAt = System.currentTimeMillis() + if (failures < 5) 10_000L else 60_000L
            }
        }
    }
}
