package dev.zyriel.voicejournal.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.zyriel.voicejournal.bench.AccuracySuite
import dev.zyriel.voicejournal.bench.BenchmarkSuite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Debug-only top-bar buttons for the on-device suites. They run on Default
 * with the application context (never the Activity) and post progress back
 * to Main, so a rotation mid-run leaks nothing and no Compose state is
 * written off-thread. try/catch, not crash visibility: an uncaught
 * exception would kill the process, which reads as a hang behind a
 * progress dialog. Strings stay inline: this never ships in a release.
 */
@Composable
internal fun DebugBenchActions() {
    val appCtx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    SuiteButton("Bench", "Benchmark", scope) { progress -> BenchmarkSuite(appCtx).runAll(progress) }
    SuiteButton("Acc", "Accuracy", scope) { progress -> AccuracySuite(appCtx).runAll(progress) }
}

@Composable
private fun SuiteButton(
    label: String,
    title: String,
    scope: CoroutineScope,
    suite: suspend (progress: (String) -> Unit) -> File,
) {
    var status by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = {
        if (status == null) {
            status = "starting"
            scope.launch {
                status = withContext(Dispatchers.Default) {
                    try {
                        val f = suite { s -> scope.launch { status = s } }
                        "Saved: ${f.name} (adb pull ${f.absolutePath})"
                    } catch (e: Exception) {
                        "FAILED: ${e.message}"
                    }
                }
            }
        }
    }) { Text(label) }
    status?.let {
        val done = it.startsWith("Saved") || it.startsWith("FAILED")
        AlertDialog(
            onDismissRequest = { if (done) status = null },
            title = { Text(title) },
            text = { Text(it) },
            confirmButton = {
                if (done) TextButton(onClick = { status = null }) { Text("Close") }
            },
        )
    }
}
