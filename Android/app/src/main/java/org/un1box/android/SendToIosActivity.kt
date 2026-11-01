package org.un1box.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.File

/**
 * "Send to iOS" in Android's share menu: copies what was shared into the Un1box folder of the device synced last, and
 * syncs it at once when that device is running. It has no screen of its own; a message says what happened.
 */
class SendToIosActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        val uris = sharedUris(intent)
        val text = if (uris.isEmpty()) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        val device = SharedFiles.lastDevice(app)
        if (uris.isEmpty() && text == null) {
            finish()
            return
        }
        if (device == null) {
            toast("Start a device in Un1box and let it sync once, then share again.")
            finish()
            return
        }
        toast("Sending to iOS...")
        // The shared files can only be read while this screen is open, so it closes when the copy is done.
        Thread({
            val message = try {
                send(app, device, uris, text)
            } catch (e: Exception) {
                "Could not send: ${e.message}"
            }
            runOnUiThread {
                toast(message)
                finish()
            }
        }, "send-to-ios").start()
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent): List<Uri> {
        val found = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { found.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { found.addAll(it) }
        }
        val clip = intent.clipData
        if (found.isEmpty() && clip != null) {
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { found.add(it) }
        }
        return found
    }

    private fun send(app: Context, device: String, uris: List<Uri>, text: String?): String {
        val folder = SharedFiles.folder(app, device)
        var count = 0
        for (uri in uris) {
            val target = free(folder, nameOf(uri))
            val input = app.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot read the shared file.")
            input.use { source -> target.outputStream().use { sink -> source.copyTo(sink) } }
            count++
        }
        if (text != null) {
            free(folder, "Shared text.txt").writeText(text)
            count++
        }
        val socket = StoreInstaller.of(app).socket
        if (socket.isNullOrEmpty()) return "Saved $count in the Un1box folder; it copies when the device runs."
        return try {
            SharedFilesSync.run(app, socket)
            "Sent $count to iOS."
        } catch (e: Exception) {
            "Saved $count in the Un1box folder, but the sync failed (${e.message}); it will retry by itself."
        }
    }

    private fun nameOf(uri: Uri): String {
        val queried = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        val raw = queried ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val clean = raw.map { if (it in "\\/\t\r\n:*?\"<>|") '_' else it }.joinToString("").trim()
        return clean.ifEmpty { "file" }
    }

    /** A file name that is not taken yet in [folder]: "name (1).ext" and on. */
    private fun free(folder: File, name: String): File {
        var file = File(folder, name)
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var n = 1
        while (file.exists()) {
            file = File(folder, "${name.substring(0, dot)} ($n)${name.substring(dot)}")
            n++
        }
        return file
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
