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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Files shared with the running guest: Sync now copies what is new or changed between this device's Un1box folder
 * and /var/mobile/Media/Un1box on the iOS device, in both directions (see [SharedFolderSync]).
 */
class SharedFilesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Shared files", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Files you put in the Un1box folder on this phone appear in /var/mobile/Media/Un1box on the iOS device, " +
                "and files it puts there appear here. Changes are copied when you press Sync now, while the device is running.",
            style = MaterialTheme.typography.bodyMedium,
        )
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
                            SharedFilesSync.run(app, socket)
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
