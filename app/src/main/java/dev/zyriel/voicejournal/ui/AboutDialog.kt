package dev.zyriel.voicejournal.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.zyriel.voicejournal.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * About: version, exact build SHA, the GPL notice the license requires,
 * and the third-party notices. License texts come from assets that the
 * build copies out of the repo-root originals, so this screen can never
 * drift from what the repo actually says.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var showNotices by remember { mutableStateOf(false) }

    if (showNotices) {
        val notices by produceState("Loading...") {
            value = readLicenseAsset(context, "licenses/THIRD_PARTY_NOTICES.md")
        }
        LicenseTextDialog("Third-party notices", notices) { showNotices = false }
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice Journal") },
        text = {
            Column {
                Text(
                    "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "A fully offline voice journal. Recording, transcription, " +
                        "and search all happen on this device; the app has no " +
                        "internet permission.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Free software under the GNU General Public License v3. " +
                        "You may redistribute and modify it under the terms of " +
                        "that license. It comes with absolutely no warranty. " +
                        "Source: github.com/ZyrielZero/voice-journal",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showNotices = true }) { Text("Third-party notices") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun LicenseTextDialog(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private suspend fun readLicenseAsset(context: Context, path: String): String =
    withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrElse { "Could not load $path: ${it.message}" }
    }
