package org.un1box.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Files shared with the running guest: the Un1box folder on this phone and /var/mobile/Media/Un1box on the iOS
 * device are kept alike, in both directions, by themselves (see [SharedFilesAuto]) or when Sync now is pressed.
 */
class SharedFilesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SharedFilesAuto.start(this)
        setContent {
            DarkTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SharedFilesScreen(onClose = { finish() })
                }
            }
        }
    }
}

@Composable
private fun SharedFilesScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var auto by remember { mutableStateOf(SharedFilesAuto.enabled(context)) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Shared files", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Files you put in the Un1box folder on this phone appear in /var/mobile/Media/Un1box on the iOS device, " +
                "and files it puts there appear here. While the device is running they are copied by themselves within " +
                "a few seconds, or press Sync now.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = auto, onCheckedChange = { auto = it; SharedFilesAuto.setEnabled(context, it) })
            Text("Sync automatically")
        }
        Button(
            onClick = {
                val app = context.applicationContext
                val socket = StoreInstaller.of(app).socket
                if (socket.isNullOrEmpty()) {
                    message = "Start a device in Un1box first, then press Sync now again."
                } else {
                    busy = true
                    message = ""
                    Thread({
                        message = try {
                            SharedFilesSync.run(app, socket).message
                        } catch (e: Exception) {
                            "Could not sync: ${e.message}"
                        }
                        busy = false
                    }, "shared-files-sync").start()
                }
            },
            enabled = !busy,
        ) { Text(if (busy) "Syncing..." else "Sync now") }
        OutlinedButton(onClick = { message = openFolder(context) }) { Text("Open the folder in Files") }
        if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodyMedium)
        if (SharedFilesAuto.status.isNotEmpty()) Text(SharedFilesAuto.status, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onClose) { Text("Close") }
    }
}

/** Opens the Un1box folder in the system Files app; returns a hint when no app can show it. */
private fun openFolder(context: Context): String {
    val uri = DocumentsContract.buildDocumentUri(context.packageName + SharedFiles.AUTHORITY_SUFFIX, SharedFiles.ROOT_ID)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        ""
    } catch (_: ActivityNotFoundException) {
        "Open the Files app and choose Un1box from its side menu."
    } catch (_: SecurityException) {
        "Open the Files app and choose Un1box from its side menu."
    }
}
