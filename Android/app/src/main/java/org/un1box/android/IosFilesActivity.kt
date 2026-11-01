package org.un1box.android

import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.concurrent.Executors

/**
 * The iOS device's media folder (/var/mobile/Media, which is all the guest's file service reaches: the camera's
 * photos in DCIM, downloads, and the shared Un1box folder), browsed on demand. A file can be saved to the phone:
 * photos to Pictures/Un1box, videos to Movies/Un1box, anything else to Download/Un1box.
 */
class IosFilesActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var session: FilesSession? = null
    private var path by mutableStateOf("/")
    private var entries by mutableStateOf(emptyList<RemoteEntry>())
    private var status by mutableStateOf("Connecting to the device...")
    private var busy by mutableStateOf(true)

    private fun join(dir: String, name: String) = if (dir == "/") "/$name" else "$dir/$name"

    private fun go(dir: String) {
        busy = true
        worker.execute {
            try {
                val connected = session ?: FilesSession(applicationContext, StoreInstaller.of(applicationContext).socket.orEmpty())
                    .also { session = it }
                val list = connected.list(dir)
                entries = list.sortedWith(compareByDescending<RemoteEntry> { it.dir }.thenBy { it.name.lowercase() })
                path = dir
                status = if (list.isEmpty()) "This folder is empty." else ""
            } catch (e: Exception) {
                session = null
                status = "Could not read the device: ${e.javaClass.simpleName}: ${e.message}"
            }
            busy = false
        }
    }

    private fun save(items: List<RemoteEntry>) {
        busy = true
        worker.execute {
            var saved = 0
            var where = ""
            try {
                val connected = session ?: throw IllegalStateException("The device is not connected.")
                for (entry in items) {
                    status = "Saving ${saved + 1} of ${items.size}: ${entry.name}"
                    val temporary = File(cacheDir, "ios-${System.nanoTime()}")
                    try {
                        connected.pull(join(path, entry.name), temporary)
                        where = saveToPhone(applicationContext, temporary, entry.name)
                    } finally {
                        temporary.delete()
                    }
                    saved++
                }
                status = "Saved $saved to $where."
            } catch (e: Exception) {
                status = "Saved $saved, then failed: ${e.message}"
            }
            busy = false
        }
    }

    private fun up() {
        if (path == "/") finish() else go(path.substringBeforeLast('/').ifEmpty { "/" })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (StoreInstaller.of(applicationContext).socket.isNullOrEmpty()) {
            status = "Start a device in Un1box first, then open this again."
            busy = false
        } else {
            go("/")
        }
        setContent {
            DarkTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BackHandler { up() }
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("iOS files", style = MaterialTheme.typography.headlineSmall)
                        Text(path, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { up() }, enabled = !busy) { Text(if (path == "/") "Close" else "Up") }
                            val files = entries.filter { !it.dir }
                            if (files.isNotEmpty()) {
                                OutlinedButton(onClick = { save(files) }, enabled = !busy) { Text("Save all ${files.size} here") }
                            }
                        }
                        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)
                        if (status.startsWith("Could not")) {
                            OutlinedButton(onClick = { go(path) }, enabled = !busy) { Text("Try again") }
                        }
                        LazyColumn(Modifier.weight(1f)) {
                            items(entries, key = { it.name }) { entry ->
                                Row(
                                    Modifier.fillMaxWidth().clickable(enabled = !busy && entry.dir) { go(join(path, entry.name)) },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                        Text(if (entry.dir) "${entry.name}/" else entry.name, style = MaterialTheme.typography.bodyLarge)
                                        if (!entry.dir) {
                                            Text(
                                                Formatter.formatShortFileSize(this@IosFilesActivity, entry.size),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                    if (!entry.dir) TextButton(onClick = { save(listOf(entry)) }, enabled = !busy) { Text("Save") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.execute { runCatching { session?.close() } }
        worker.shutdown()
    }

    /** Copies [file] into the phone's gallery or downloads as [name]; returns the folder it went to. */
    private fun saveToPhone(context: Context, file: File, name: String): String {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val (collection, folder) = when {
            mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "Pictures/Un1box"
            mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "Movies/Un1box"
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to "Download/Un1box"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("Could not create $name on the phone.")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IllegalStateException("Could not write $name on the phone.")
            out.use { target -> file.inputStream().use { it.copyTo(target) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return folder
    }
}
