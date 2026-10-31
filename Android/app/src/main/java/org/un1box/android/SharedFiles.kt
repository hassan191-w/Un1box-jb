package org.un1box.android

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * The folder shared with the guest. On Android it is a folder of this app that the Files app shows as "Un1box"
 * (SharedFolderProvider); on the iOS device it is /var/mobile/Media/Un1box. [SharedFolderSync] keeps the two alike
 * through the guest's AFC service, by way of the app-service helper's files-session.
 */
object SharedFiles {
    /** Unused now (the screen reads the socket from StoreInstaller); kept so an earlier MainActivity edit still builds. */
    @Volatile var socket: String = ""
    @Volatile var deviceId: String = ""

    /** The folder on the iOS side, inside the guest's media folder. */
    const val REMOTE = "/Un1box"
    const val AUTHORITY_SUFFIX = ".shared"
    const val ROOT_ID = "un1box"

    internal fun safe(id: String) = id.ifEmpty { "device" }.replace(Regex("[^A-Za-z0-9._-]"), "_")

    internal fun base(context: Context): File = File(context.getExternalFilesDir(null), "shared").apply { mkdirs() }

    /** This device's side of the shared folder. */
    fun folder(context: Context, deviceId: String): File = File(base(context), safe(deviceId)).apply { mkdirs() }

    /** The device whose folder was synced last, or null before any sync. */
    fun lastDevice(context: Context): String? =
        context.getSharedPreferences("shared_files", Context.MODE_PRIVATE).getString("lastDevice", null)

    internal fun rememberDevice(context: Context, device: String) {
        context.getSharedPreferences("shared_files", Context.MODE_PRIVATE).edit().putString("lastDevice", device).apply()
    }
}

/** Makes the shared folder appear in the Files app as "Un1box", one folder per device, with create and delete. */
class SharedFolderProvider : DocumentsProvider() {
    private val base: File get() = SharedFiles.base(context!!)

    override fun onCreate(): Boolean {
        // Android creates the app's providers when its process starts, which is when automatic sync can begin.
        context?.let { SharedFilesAuto.start(it) }
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, SharedFiles.ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, SharedFiles.ROOT_ID)
            add(Root.COLUMN_TITLE, "Un1box")
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY)
            add(Root.COLUMN_ICON, context!!.applicationInfo.icon)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOC_COLUMNS)
        addFile(cursor, documentId, fileFor(documentId))
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: DOC_COLUMNS)
        fileFor(parentDocumentId).listFiles().orEmpty().forEach { addFile(cursor, "$parentDocumentId/${it.name}", it) }
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileFor(parentDocumentId)
        if (displayName.contains('/') || displayName.isEmpty()) throw FileNotFoundException(displayName)
        val file = File(parent, displayName)
        if (mimeType == Document.MIME_TYPE_DIR) file.mkdirs() else file.createNewFile()
        return "$parentDocumentId/$displayName"
    }

    override fun deleteDocument(documentId: String) {
        fileFor(documentId).deleteRecursively()
    }

    private fun fileFor(documentId: String): File {
        val relative = documentId.removePrefix(SharedFiles.ROOT_ID).trimStart('/')
        val file = if (relative.isEmpty()) base else File(base, relative)
        // Nothing outside the shared folder (../).
        if (!file.canonicalPath.startsWith(base.canonicalPath)) throw FileNotFoundException(documentId)
        if (!file.exists()) throw FileNotFoundException(documentId)
        return file
    }

    private fun addFile(cursor: MatrixCursor, id: String, file: File) {
        val directory = file.isDirectory
        val flags = if (directory) Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE
        else Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, id)
            add(Document.COLUMN_DISPLAY_NAME, if (id == SharedFiles.ROOT_ID) "Un1box" else file.name)
            add(Document.COLUMN_SIZE, file.length())
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
            add(
                Document.COLUMN_MIME_TYPE,
                if (directory) Document.MIME_TYPE_DIR
                else MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream",
            )
        }
    }

    private companion object {
        val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_ICON)
        val DOC_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_MIME_TYPE,
        )
    }
}

internal data class RemoteEntry(val name: String, val dir: Boolean, val size: Long, val mtime: Long)

/**
 * One files-session of the app-service helper: a single child process and one AFC connection to the guest, taking
 * a request per line and answering each with a line. A stuck call can only hang this child, which the idle
 * deadline kills.
 */
internal class FilesSession(context: Context, socket: String) : Closeable {
    class Failure(message: String) : Exception(message)

    private val process: Process
    private val reader: BufferedReader
    private val writer: OutputStream

    /** The guest's own device id (empty if it would not say), which keeps one shared folder per device. */
    val udid: String
    @Volatile private var lastActivity = System.nanoTime()
    @Volatile private var closed = false

    init {
        val helper = File(context.applicationInfo.nativeLibraryDir, "liblt_appservice.so")
        if (!helper.canExecute()) throw Failure("Guest app services are not included in this build.")
        process = ProcessBuilder(helper.absolutePath, "files-session", "--socket", socket).redirectErrorStream(true).start()
        reader = process.inputStream.bufferedReader()
        writer = process.outputStream
        Thread({
            try {
                while (!closed && process.isAlive) {
                    Thread.sleep(1000)
                    if (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - lastActivity) > 600) {
                        process.destroyForcibly()
                        break
                    }
                }
            } catch (_: InterruptedException) {
            }
        }, "shared-files-watchdog").apply { isDaemon = true; start() }
        udid = read().optString("udid") // "ready", or the reason the guest could not be reached
    }

    private fun read(): JSONObject {
        while (true) {
            val line = reader.readLine() ?: throw Failure("The guest app service stopped.")
            lastActivity = System.nanoTime()
            if (line.isBlank()) continue
            val value = try { JSONObject(line) } catch (_: JSONException) { continue }
            if (value.has("error")) throw Failure(value.optString("error"))
            return value.optJSONObject("result") ?: continue
        }
    }

    @Synchronized
    private fun call(vararg parts: String): JSONObject {
        require(parts.none { it.contains('\t') || it.contains('\n') || it.contains('\r') }) { "bad file name" }
        writer.write((parts.joinToString("\t") + "\n").toByteArray(Charsets.UTF_8))
        writer.flush()
        lastActivity = System.nanoTime()
        return read()
    }

    fun list(dir: String): List<RemoteEntry> {
        val entries = call("LIST", dir).optJSONArray("entries") ?: return emptyList()
        return (0 until entries.length()).map { entries.getJSONObject(it) }
            .map { RemoteEntry(it.getString("name"), it.getBoolean("dir"), it.optLong("bytes"), it.optLong("mtime")) }
    }

    fun mkdir(path: String) { call("MKDIR", path) }
    fun remove(path: String) { call("RM", path) }
    fun pull(remote: String, to: File) { call("PULL", remote, to.absolutePath) }
    fun push(from: File, remote: String) { call("PUSH", from.absolutePath, remote) }

    override fun close() {
        closed = true
        runCatching { writer.close() }
        if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
    }
}

/**
 * Keeps this device's Android folder and the guest's /Un1box alike, in both directions. What each side had at the
 * last sync is remembered, so a file that is new or changed on one side is copied to the other, and one that was
 * deleted on one side (and not changed on the other) is deleted on the other too. A file changed on both sides
 * goes to whichever was changed last. A folder is made on both sides, and one deleted on a side is deleted on the other
 * too when nothing inside it has changed since the last sync; otherwise it stays, with what changed.
 */
internal class SharedFolderSync(
    private val local: File,
    private val remoteRoot: String,
    private val io: FilesSession,
    private val stateFile: File,
) {
    private data class Seen(val local: Long, val remote: Long, val size: Long)

    private val state = HashMap<String, Seen>()

    /** The folders that were on both sides at the last sync. */
    private val knownDirs = HashSet<String>()
    var sent = 0
    var received = 0
    var removed = 0
    var failed = 0
    var lastError = ""

    fun run() {
        load()
        try {
            io.mkdir(remoteRoot)
            syncDir("")
        } finally {
            save()
        }
    }

    private fun syncable(name: String) =
        name.isNotEmpty() && !name.endsWith(".part") && name.none { it == '\t' || it == '\n' || it == '\r' }

    private fun remotePath(relative: String) = if (relative.isEmpty()) remoteRoot else "$remoteRoot/$relative"

    private fun attempt(action: () -> Unit) {
        try {
            action()
        } catch (e: FilesSession.Failure) {
            failed++
            lastError = e.message ?: "unknown error"
        }
    }

    private fun syncDir(relative: String) {
        val dir = if (relative.isEmpty()) local else File(local, relative)
        dir.mkdirs()
        val remote = io.list(remotePath(relative)).filter { syncable(it.name) }.associateBy { it.name }
        val here = dir.listFiles().orEmpty().filter { syncable(it.name) }.associateBy { it.name }
        for (name in (remote.keys + here.keys).toSortedSet()) {
            val r = remote[name]
            val l = here[name]
            val path = if (relative.isEmpty()) name else "$relative/$name"
            if ((r != null && r.dir && l != null && !l.isDirectory) || (r != null && !r.dir && l != null && l.isDirectory)) {
                failed++
                lastError = "$path is a folder on one side and a file on the other"
                continue
            }
            if ((r != null && r.dir) || (l != null && l.isDirectory)) {
                attempt { syncFolder(path, name, dir, l, r) }
                continue
            }
            attempt { syncFile(path, l, r) }
        }
        attempt { refresh(relative, dir) }
    }

    /** A folder on one side or both: made on the other side, or deleted there if it was deleted here. */
    private fun syncFolder(path: String, name: String, parent: File, l: File?, r: RemoteEntry?) {
        val remote = remotePath(path)
        val known = path in knownDirs
        if (l == null && known && remoteTreeUnchanged(remote, path)) {
            removeRemoteTree(remote, path)
            return
        }
        if (r == null && l != null && known && localTreeUnchanged(l, path)) {
            l.deleteRecursively()
            forget(path)
            return
        }
        if (r == null) io.mkdir(remote)
        if (l == null) File(parent, name).mkdirs()
        syncDir(path)
        knownDirs.add(path)
    }

    /** Whether every file in this folder is as it was at the last sync, with nothing new. */
    private fun localTreeUnchanged(dir: File, relative: String): Boolean = dir.listFiles().orEmpty().all { f ->
        val path = "$relative/${f.name}"
        if (f.isDirectory) localTreeUnchanged(f, path)
        else state[path]?.let { it.local == f.lastModified() && it.size == f.length() } == true
    }

    private fun remoteTreeUnchanged(remoteDir: String, relative: String): Boolean = io.list(remoteDir).all { e ->
        val path = "$relative/${e.name}"
        if (e.dir) remoteTreeUnchanged("$remoteDir/${e.name}", path)
        else state[path]?.let { it.remote == e.mtime && it.size == e.size } == true
    }

    private fun removeRemoteTree(remoteDir: String, relative: String) {
        for (e in io.list(remoteDir)) {
            if (e.dir) removeRemoteTree("$remoteDir/${e.name}", "$relative/${e.name}") else io.remove("$remoteDir/${e.name}")
        }
        io.remove(remoteDir)
        forget(relative)
    }

    /** Drops what is remembered about a folder and everything in it. */
    private fun forget(path: String) {
        state.keys.removeAll { it.startsWith("$path/") }
        knownDirs.removeAll { it == path || it.startsWith("$path/") }
    }

    private fun syncFile(path: String, l: File?, r: RemoteEntry?) {
        val previous = state[path]
        val remote = remotePath(path)
        if (l != null && r != null) {
            val localChanged = previous == null || l.lastModified() != previous.local || l.length() != previous.size
            val remoteChanged = previous == null || r.mtime != previous.remote || r.size != previous.size
            if (!localChanged && !remoteChanged) return
            // The same size the first time both sides are seen: take them as the same file.
            if (previous == null && l.length() == r.size) return
            val send = if (localChanged && remoteChanged) l.lastModified() >= r.mtime else localChanged
            if (send) {
                io.push(l, remote)
                sent++
            } else {
                io.pull(remote, l)
                received++
            }
        } else if (l != null) {
            if (previous != null && l.lastModified() == previous.local && l.length() == previous.size) {
                l.delete()
                state.remove(path)
                removed++
            } else {
                io.push(l, remote)
                sent++
            }
        } else if (r != null) {
            if (previous != null && r.mtime == previous.remote && r.size == previous.size) {
                io.remove(remote)
                state.remove(path)
                removed++
            } else {
                io.pull(remote, File(local, path))
                received++
            }
        }
    }

    /** What both sides look like now, for the next sync to compare with. */
    private fun refresh(relative: String, dir: File) {
        val remoteNow = io.list(remotePath(relative)).filter { !it.dir && syncable(it.name) }.associateBy { it.name }
        val localNow = dir.listFiles().orEmpty().filter { it.isFile && syncable(it.name) }.associateBy { it.name }
        val prefix = if (relative.isEmpty()) "" else "$relative/"
        for ((name, file) in localNow) {
            val r = remoteNow[name] ?: continue
            state[prefix + name] = Seen(file.lastModified(), r.mtime, file.length())
        }
        state.keys.removeAll { key ->
            key.startsWith(prefix) && !key.substring(prefix.length).contains('/') &&
                key.substring(prefix.length) !in localNow && key.substring(prefix.length) !in remoteNow
        }
    }

    private fun load() {
        state.clear()
        knownDirs.clear()
        val text = runCatching { stateFile.readText() }.getOrNull() ?: return
        val saved = runCatching { JSONObject(text) }.getOrNull() ?: return
        // The first version kept the files at the top level.
        val files = saved.optJSONObject("files") ?: saved
        for (key in files.keys()) {
            val values = files.optJSONArray(key) ?: continue
            if (values.length() == 3) state[key] = Seen(values.getLong(0), values.getLong(1), values.getLong(2))
        }
        saved.optJSONArray("dirs")?.let { dirs -> for (i in 0 until dirs.length()) knownDirs.add(dirs.getString(i)) }
    }

    private fun save() {
        val files = JSONObject()
        for ((key, seen) in state) files.put(key, JSONArray(listOf(seen.local, seen.remote, seen.size)))
        val saved = JSONObject().put("files", files).put("dirs", JSONArray(knownDirs.toList()))
        runCatching { stateFile.writeText(saved.toString()) }
    }
}

internal class SyncOutcome(val message: String, val remoteSignature: String)

/** One sync of the running guest's shared folder; calls are serialised. */
internal object SharedFilesSync {
    private val lock = Any()

    /** One sync, tried up to three times: the guest may still be starting, or still closing the last session. */
    fun run(context: Context, socket: String): SyncOutcome = synchronized(lock) {
        var failure: Exception = FilesSession.Failure("The guest did not answer.")
        for (attempt in 1..3) {
            try {
                return@synchronized once(context, socket)
            } catch (e: FilesSession.Failure) {
                failure = e
                if (attempt < 3) Thread.sleep(2000)
            }
        }
        throw failure
    }

    private fun once(context: Context, socket: String): SyncOutcome =
        FilesSession(context, socket).use { session ->
            val device = SharedFiles.safe(session.udid)
            SharedFiles.rememberDevice(context, device)
            val folder = SharedFiles.folder(context, device)
            val stateFile = File(context.filesDir, "shared-state-$device.json")
            val sync = SharedFolderSync(folder, SharedFiles.REMOTE, session, stateFile)
            sync.run()
            val done = "Sent ${sync.sent}, received ${sync.received}, removed ${sync.removed}."
            val message = if (sync.failed == 0) done else "$done ${sync.failed} could not be copied: ${sync.lastError}"
            SyncOutcome(message, remoteSignature(session))
        }

    /** The guest's folder as one number, to notice a change made there. */
    fun peek(context: Context, socket: String): String =
        synchronized(lock) { FilesSession(context, socket).use { remoteSignature(it) } }

    private fun remoteSignature(io: FilesSession): String {
        val text = StringBuilder()
        fun walk(dir: String) {
            for (e in io.list(dir).sortedBy { it.name }) {
                text.append(dir).append('/').append(e.name).append('|').append(e.dir).append('|')
                    .append(e.size).append('|').append(e.mtime).append('\n')
                if (e.dir) walk("$dir/${e.name}")
            }
        }
        walk(SharedFiles.REMOTE)
        return text.toString().hashCode().toString()
    }

    /** The phone's side of the shared folders as one number. */
    fun localSignature(context: Context): String {
        val base = SharedFiles.base(context)
        val text = StringBuilder()
        base.walkTopDown().filter { it != base && !it.name.endsWith(".part") }.sortedBy { it.path }.forEach {
            text.append(it.path).append('|').append(it.length()).append('|').append(it.lastModified()).append('\n')
        }
        return text.toString().hashCode().toString()
    }
}
