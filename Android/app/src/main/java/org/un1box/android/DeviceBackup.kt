package org.un1box.android

import android.content.Context
import android.os.Bundle
import android.provider.DocumentsContract
import android.system.Os
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A prepared device is one folder of the library, library/records/<id>: record.json, the prepared base, the state (what
 * the guest has written) and the assets. Backing it up packs that folder in a zip; restoring unpacks it as a new
 * record, with the permissions the engine insists on (the base and assets read-only, the state private).
 */
internal object DeviceBackup {
    class Cancelled : Exception("Cancelled")
    class Failure(message: String) : Exception(message)

    data class Record(val id: String, val name: String, val subtitle: String)

    private fun root(context: Context) = File(context.filesDir, "library/records")

    fun records(context: Context): List<Record> =
        root(context).listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.mapNotNull { dir ->
            val json = runCatching { JSONObject(File(dir, "record.json").readText()) }.getOrNull() ?: return@mapNotNull null
            Record(dir.name, json.optString("name", dir.name), "${json.optString("board")} · ${json.optString("build")}")
        }.sortedBy { it.name.lowercase() }

    /** Packs device [id] into [out] as a zip. [progress] gets the bytes read so far. */
    fun backup(context: Context, id: String, out: OutputStream, progress: (Long) -> Unit, cancelled: () -> Boolean) {
        val dir = File(root(context), id)
        if (!dir.isDirectory) throw Failure("That device is not here any more.")
        var done = 0L
        val buffer = ByteArray(1 shl 16)
        ZipOutputStream(BufferedOutputStream(out, 1 shl 16)).use { zip ->
            zip.setLevel(1)
            for (file in dir.walkTopDown()) {
                if (cancelled()) throw Cancelled()
                if (java.nio.file.Files.isSymbolicLink(file.toPath()) || file.name == ".lease") continue
                val name = file.relativeTo(dir.parentFile!!).path.replace(File.separatorChar, '/')
                if (file.isDirectory) {
                    zip.putNextEntry(ZipEntry("$name/"))
                    zip.closeEntry()
                } else {
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            zip.write(buffer, 0, n)
                            done += n
                            progress(done)
                            if (cancelled()) throw Cancelled()
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    /** Unpacks a backup as a new device; returns its name. Nothing is left behind when it fails. */
    fun restore(context: Context, input: InputStream, progress: (Long) -> Unit, cancelled: () -> Boolean): String {
        val records = root(context).apply { mkdirs() }
        val stage = File(records, ".record-restore-${System.nanoTime()}").apply { mkdirs() }
        try {
            var id: String? = null
            var done = 0L
            val buffer = ByteArray(1 shl 16)
            ZipInputStream(BufferedInputStream(input, 1 shl 16)).use { zip ->
                while (true) {
                    if (cancelled()) throw Cancelled()
                    val entry = zip.nextEntry ?: break
                    val parts = entry.name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
                    if (parts.isEmpty() || parts.any { it == ".." || it == "." }) throw Failure("The backup has an unsafe file name.")
                    val top = parts[0]
                    if (id == null) {
                        if (!Regex("[A-Za-z0-9_-]{1,63}").matches(top)) throw Failure("This is not an Un1box device backup.")
                        id = top
                    } else if (top != id) {
                        throw Failure("This backup holds more than one device.")
                    }
                    val target = File(stage, parts.joinToString("/"))
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        var length = 0L
                        RandomAccessFile(target, "rw").use { file ->
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                // Empty stretches stay holes, so a mostly empty image does not fill the phone.
                                if (isZero(buffer, n)) file.seek(file.filePointer + n) else file.write(buffer, 0, n)
                                length += n
                                done += n
                                progress(done)
                                if (cancelled()) throw Cancelled()
                            }
                            file.setLength(length)
                        }
                    }
                }
            }
            val device = id ?: throw Failure("The file is empty.")
            val record = File(stage, device)
            val meta = runCatching { JSONObject(File(record, "record.json").readText()) }.getOrNull()
            if (meta == null || meta.optInt("schema") != 1 || meta.optString("id") != device ||
                !File(record, "base/device.lock.json").isFile
            ) throw Failure("This is not an Un1box device backup.")
            if (File(records, device).exists()) throw Failure("This device is already here. Delete it in Un1box first, then restore.")
            secure(record)
            if (!record.renameTo(File(records, device))) throw Failure("Could not put the device in place.")
            return meta.optString("name", device)
        } finally {
            deleteTree(stage)
        }
    }

    private fun isZero(buffer: ByteArray, n: Int): Boolean {
        for (i in 0 until n) if (buffer[i].toInt() != 0) return false
        return true
    }

    /** The modes the engine checks for: base and assets read-only, state and the record private. */
    private fun secure(record: File) {
        fun tree(dir: File, dirMode: Int, fileMode: Int) {
            Os.chmod(dir.path, dirMode)
            for (child in dir.listFiles().orEmpty()) if (child.isDirectory) tree(child, dirMode, fileMode) else Os.chmod(child.path, fileMode)
        }
        if (File(record, "base").isDirectory) tree(File(record, "base"), 320, 256) // 0500, 0400
        if (File(record, "assets").isDirectory) tree(File(record, "assets"), 320, 256)
        if (File(record, "state").isDirectory) tree(File(record, "state"), 448, 384) // 0700, 0600
        Os.chmod(File(record, "record.json").path, 384)
        Os.chmod(record.path, 448)
    }

    private fun deleteTree(file: File) {
        runCatching { Os.chmod(file.path, 448) }
        if (file.isDirectory) for (child in file.listFiles().orEmpty()) deleteTree(child)
        file.delete()
    }
}

/** Back up a prepared device to a file of your choice, or restore one. The device must be stopped. */
class DeviceBackupActivity : ComponentActivity() {
    private var records by mutableStateOf(emptyList<DeviceBackup.Record>())
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var megabytes by mutableStateOf(0L)
    @Volatile private var cancel = false
    private var pending: DeviceBackup.Record? = null

    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val record = pending
        if (uri != null && record != null) {
            work("Backing up ${record.name}...") { progress ->
                try {
                    val out = contentResolver.openOutputStream(uri) ?: throw DeviceBackup.Failure("Cannot write that file.")
                    out.use { DeviceBackup.backup(this, record.id, it, progress) { cancel } }
                } catch (e: Exception) {
                    runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
                    throw e
                }
                "Saved. Keep this file somewhere safe; Restore brings the device back from it."
            }
        }
    }

    private val restoreBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            work("Restoring...") { progress ->
                val input = contentResolver.openInputStream(uri) ?: throw DeviceBackup.Failure("Cannot read that file.")
                val name = input.use { DeviceBackup.restore(this, it, progress) { cancel } }
                "Restored $name. Reopen Un1box and pick it in the device list."
            }
        }
    }

    private fun stopped(): Boolean {
        if (StoreInstaller.of(applicationContext).socket.isNullOrEmpty()) return true
        message = "Stop the device in Un1box first, then try again."
        return false
    }

    private fun work(label: String, job: (progress: (Long) -> Unit) -> String) {
        if (busy) return
        busy = true
        cancel = false
        megabytes = 0
        message = label
        Thread({
            val result = try {
                job { bytes -> megabytes = bytes shr 20 }
            } catch (_: DeviceBackup.Cancelled) {
                "Cancelled."
            } catch (e: Exception) {
                "Failed: ${e.message}"
            }
            records = DeviceBackup.records(this)
            message = result
            busy = false
        }, "device-backup").start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        records = DeviceBackup.records(this)
        setContent {
            DarkTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Back up and restore", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Saves a prepared device, with everything in it, as one file, so reinstalling Un1box does not " +
                                "mean preparing it again. Stop the device first. The file can be large.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(records, key = { it.id }) { record ->
                                Column(Modifier.fillMaxWidth()) {
                                    Text(record.name, style = MaterialTheme.typography.titleMedium)
                                    Text(record.subtitle, style = MaterialTheme.typography.bodySmall)
                                    Button(
                                        enabled = !busy,
                                        onClick = {
                                            if (stopped()) {
                                                pending = record
                                                val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                                                val safe = record.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                                                createBackup.launch("un1box-$safe-$day.zip")
                                            }
                                        },
                                    ) { Text("Back up") }
                                }
                            }
                        }
                        if (records.isEmpty()) Text("No prepared device yet.", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { if (stopped()) restoreBackup.launch(arrayOf("*/*")) },
                        ) { Text("Restore from a backup file") }
                        if (busy) {
                            Text("$megabytes MB so far...", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { cancel = true }) { Text("Cancel") }
                        }
                        if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { finish() }) { Text("Close") }
                    }
                }
            }
        }
    }
}
