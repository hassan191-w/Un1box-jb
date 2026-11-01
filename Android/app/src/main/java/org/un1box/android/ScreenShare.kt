package org.un1box.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Two things made from Android's own screen capture, so they work whatever is on screen (show the device full screen
 * first): the screen shown live to a browser on the same Wi-Fi (a small web server, behind a random address that is
 * only shown here), and the screen recorded to a video in Movies/Un1box. Each needs the system's permission prompt
 * every time. Only one of them runs at a time.
 */
class ScreenShareService : Service() {
    companion object {
        const val MODE_VIEW = "view"
        const val MODE_RECORD = "record"
        const val ACTION_STOP = "org.un1box.android.SCREEN_STOP"
        const val EXTRA_MODE = "mode"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "screen_share"
        private const val NOTIFICATION = 7243
        private const val FIRST_PORT = 8080

        var running by mutableStateOf(false)
        var status by mutableStateOf("")
        var address by mutableStateOf("")
    }

    private val worker = HandlerThread("screen-share").apply { start() }
    private val handler by lazy { Handler(worker.looper) }
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var recordUri: Uri? = null
    private var recordDescriptor: ParcelFileDescriptor? = null
    private var server: ServerSocket? = null
    private var token = ""
    private var stopping = false
    @Volatile private var frame = ByteArray(0)
    @Volatile private var frameNumber = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            finish("Stopped.")
            return START_NOT_STICKY
        }
        val mode = intent.getStringExtra(EXTRA_MODE).orEmpty()
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        stopping = false
        startForeground(NOTIFICATION, notification(if (mode == MODE_RECORD) "Recording the screen" else "Showing the screen"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        try {
            if (data == null) throw IllegalStateException("No permission was given.")
            begin(mode, code, data)
            running = true
        } catch (e: Exception) {
            finish("Could not start: ${e.message}")
        }
        return START_NOT_STICKY
    }

    private fun begin(mode: String, code: Int, data: Intent) {
        val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
            ?: throw IllegalStateException("The system refused the screen capture.")
        projection = p
        // Android 14 insists on a callback before the capture is set up.
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { handler.post { finish("The screen capture ended.") } }
        }, handler)
        val metrics = resources.displayMetrics
        val shortSide = minOf(metrics.widthPixels, metrics.heightPixels)
        val scale = minOf(1f, (if (mode == MODE_VIEW) 540f else 720f) / shortSide)
        val width = (metrics.widthPixels * scale).toInt() and 1.inv()
        val height = (metrics.heightPixels * scale).toInt() and 1.inv()
        if (mode == MODE_VIEW) startView(p, width, height, metrics.densityDpi) else startRecord(p, width, height, metrics.densityDpi)
    }

    // ---- live view in a browser ----

    private fun startView(p: MediaProjection, width: Int, height: Int, dpi: Int) {
        val images = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = images
        var last = 0L
        images.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - last >= 100) { // about ten pictures a second
                    last = now
                    val plane = image.planes[0]
                    val padded = plane.rowStride / plane.pixelStride
                    val bitmap = Bitmap.createBitmap(padded, height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(plane.buffer)
                    val visible = if (padded == width) bitmap else Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    val jpeg = ByteArrayOutputStream()
                    visible.compress(Bitmap.CompressFormat.JPEG, 60, jpeg)
                    if (visible !== bitmap) visible.recycle()
                    bitmap.recycle()
                    frame = jpeg.toByteArray()
                    frameNumber++
                }
            } finally {
                image.close()
            }
        }, handler)
        display = p.createVirtualDisplay(
            "un1box-view", width, height, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, images.surface, null, handler,
        )
        openServer()
    }

    private fun openServer() {
        token = (1..6).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")
        var socket: ServerSocket? = null
        var port = FIRST_PORT
        while (socket == null && port < FIRST_PORT + 10) {
            socket = try { ServerSocket(port) } catch (_: IOException) { port++; null }
        }
        val open = socket ?: throw IllegalStateException("No free port for the web page.")
        server = open
        address = "http://${localAddress()}:$port/$token"
        Thread({
            while (!open.isClosed) {
                val client = try { open.accept() } catch (_: IOException) { break }
                Thread({ serve(client) }, "screen-share-client").start()
            }
        }, "screen-share-server").start()
    }

    private fun localAddress(): String =
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
            ?: "this-phone"

    private fun serve(client: Socket) {
        client.use {
            try {
                client.soTimeout = 10_000
                val input = client.getInputStream().bufferedReader()
                val request = input.readLine() ?: return
                while (true) if ((input.readLine() ?: break).isEmpty()) break
                val path = request.split(' ').getOrNull(1).orEmpty()
                val out = client.getOutputStream()
                when (path) {
                    "/$token", "/$token/" -> reply(out, "200 OK", "text/html; charset=utf-8", page().toByteArray())
                    "/$token/frame.jpg" -> reply(out, "200 OK", "image/jpeg", frame)
                    "/$token/stream" -> stream(client)
                    else -> reply(out, "404 Not Found", "text/plain", "Not found".toByteArray())
                }
            } catch (_: IOException) {
            }
        }
    }

    private fun page() = "<!doctype html><meta name=viewport content=\"width=device-width,initial-scale=1\"><title>Un1box</title>" +
        "<body style=\"margin:0;background:#000;display:flex;justify-content:center\">" +
        "<img src=\"/$token/stream\" style=\"max-width:100%;max-height:100vh\"></body>"

    private fun reply(out: OutputStream, status: String, type: String, body: ByteArray) {
        out.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    private fun stream(client: Socket) {
        client.soTimeout = 0
        val out = BufferedOutputStream(client.getOutputStream())
        out.write("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        var sent = -1L
        while (!client.isClosed && server?.isClosed == false) {
            val jpeg = frame
            val number = frameNumber
            if (number != sent && jpeg.isNotEmpty()) {
                sent = number
                out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n".toByteArray())
                out.write(jpeg)
                out.write("\r\n".toByteArray())
                out.flush()
            } else {
                Thread.sleep(30)
            }
        }
    }

    // ---- recording ----

    private fun startRecord(p: MediaProjection, width: Int, height: Int, dpi: Int) {
        val name = "un1box-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Un1box")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create the video file.")
        recordUri = uri
        val descriptor = contentResolver.openFileDescriptor(uri, "w") ?: throw IllegalStateException("Could not write the video file.")
        recordDescriptor = descriptor
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        recorder = r
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoSize(width, height)
        r.setVideoFrameRate(30)
        r.setVideoEncodingBitRate(4_000_000)
        r.setOutputFile(descriptor.fileDescriptor)
        r.prepare()
        display = p.createVirtualDisplay(
            "un1box-record", width, height, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler,
        )
        r.start()
        address = ""
        status = "Recording... open the device on screen, then press Stop here or in the notification."
    }

    // ---- ending ----

    private fun finish(message: String) {
        if (stopping) return
        stopping = true
        runCatching { server?.close() }
        server = null
        runCatching { display?.release() }
        display = null
        runCatching { reader?.close() }
        reader = null
        var saved = false
        recorder?.let { r ->
            saved = runCatching { r.stop() }.isSuccess
            runCatching { r.release() }
        }
        recorder = null
        runCatching { recordDescriptor?.close() }
        recordDescriptor = null
        recordUri?.let { uri ->
            if (saved) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                runCatching { contentResolver.update(uri, done, null, null) }
            } else {
                runCatching { contentResolver.delete(uri, null, null) }
            }
        }
        val wasRecording = recordUri != null
        recordUri = null
        runCatching { projection?.stop() }
        projection = null
        running = false
        address = ""
        status = if (wasRecording && saved) "Saved the video in Movies/Un1box." else message
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Screen sharing", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Un1box")
            .setContentText(text)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }
}

/** Starts and stops the screen tools; the system asks permission each time. */
class ScreenToolsActivity : ComponentActivity() {
    private var pendingMode = ""

    private val request = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            ScreenShareService.status = "Starting..."
            startForegroundService(
                Intent(this, ScreenShareService::class.java).putExtra(ScreenShareService.EXTRA_MODE, pendingMode)
                    .putExtra(ScreenShareService.EXTRA_CODE, result.resultCode).putExtra(ScreenShareService.EXTRA_DATA, data),
            )
        } else {
            ScreenShareService.status = "The permission was not given."
        }
    }

    private fun ask(mode: String) {
        pendingMode = mode
        request.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DarkTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val context = LocalContext.current
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Screen tools", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Show the screen live in a browser on the same Wi-Fi, or record it to a video. Open the device " +
                                "full screen first, then start one of them here and go back to the device. It shows what is " +
                                "on the phone's screen, with no sound.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!ScreenShareService.running) {
                            Button(onClick = { ask(ScreenShareService.MODE_VIEW) }) { Text("Show in a browser") }
                            OutlinedButton(onClick = { ask(ScreenShareService.MODE_RECORD) }) { Text("Record the screen") }
                        } else {
                            Button(onClick = { startService(Intent(context, ScreenShareService::class.java).setAction(ScreenShareService.ACTION_STOP)) }) {
                                Text("Stop")
                            }
                        }
                        if (ScreenShareService.address.isNotEmpty()) {
                            Text("Open this address in a browser on the same Wi-Fi:", style = MaterialTheme.typography.bodyMedium)
                            Text(ScreenShareService.address, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Anyone on your Wi-Fi who has this address can watch, so keep it to yourself.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(onClick = {
                                context.getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(ClipData.newPlainText("address", ScreenShareService.address))
                            }) { Text("Copy the address") }
                        }
                        if (ScreenShareService.status.isNotEmpty()) {
                            Text(ScreenShareService.status, style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(onClick = { finish() }) { Text("Close") }
                    }
                }
            }
        }
    }
}
