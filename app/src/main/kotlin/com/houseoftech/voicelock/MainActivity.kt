package com.houseoftech.voicelock

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Milestone 0 harness. Deliberately ugly: its only job is to start the
 * listener with the right permissions and show the numbers the "done" test is
 * scored on. The user says the phrase, watches "detections" tick, and presses
 * "false positive" whenever it ticks on its own.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { SpikeScreen() }
            }
        }
    }
}

@Composable
private fun SpikeScreen() {
    val ctx = LocalContext.current

    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    var micOk by remember { mutableStateOf(granted(Manifest.permission.RECORD_AUDIO)) }
    var notifOk by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        micOk = r[Manifest.permission.RECORD_AUDIO] == true || micOk
        notifOk = Build.VERSION.SDK_INT < 33 || r[Manifest.permission.POST_NOTIFICATIONS] == true || notifOk
    }

    // Poll the log's counters once a second; the service updates them from its
    // own thread and this is a spike, not a product.
    var detections by remember { mutableIntStateOf(0) }
    var falsePositives by remember { mutableIntStateOf(0) }
    var lastAt by remember { mutableStateOf(0L) }
    var battery by remember { mutableIntStateOf(-1) }
    var running by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            detections = SpikeLog.detections.get()
            falsePositives = SpikeLog.falsePositives.get()
            lastAt = SpikeLog.lastDetectionAt
            battery = SpikeLog.lastBatteryPct
            running = SpikeLog.serviceStartedAt != 0L
            if (showLog) logText = SpikeLog.readAll(ctx)
            delay(1000)
        }
    }

    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("VoiceLock — Milestone 0 spike", style = MaterialTheme.typography.titleLarge)
        Text(
            if (BuildConfig.PICOVOICE_ACCESS_KEY.isBlank())
                "No AccessKey. Add PICOVOICE_ACCESS_KEY=... to local.properties and rebuild."
            else "AccessKey present.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (!micOk || !notifOk) {
            Button(onClick = {
                val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
                ask.launch(wanted.toTypedArray())
            }) { Text("Grant microphone + notifications") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = micOk && notifOk && !running, onClick = { ListenService.start(ctx) }) { Text("Start listening") }
            OutlinedButton(enabled = running, onClick = { ListenService.stop(ctx) }) { Text("Stop") }
        }

        Text("Service: ${if (running) "RUNNING" else "stopped"}", style = MaterialTheme.typography.titleMedium)
        Text("Detections: $detections")
        Text("False positives (marked): $falsePositives")
        Text("Last detection: ${if (lastAt == 0L) "—" else fmt.format(Date(lastAt))}")
        Text("Battery: ${if (battery < 0) "—" else "$battery%"}")

        Text(
            "Score: say the phrase, watch Detections tick. If it ticks when you did NOT say it, press the button below.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(enabled = detections > falsePositives, onClick = { SpikeLog.falsePositive(ctx) }) {
            Text("That one was a FALSE positive")
        }

        Text("Debug triggers — exercise each mechanic without audio", style = MaterialTheme.typography.titleMedium)
        Text(
            "These fire the same Trigger the recogniser or clap detector would. The service must be running for anything to happen.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = running, onClick = { TriggerBus.fire(Trigger.DebugLock) }) { Text("Lock") }
            OutlinedButton(enabled = running, onClick = { TriggerBus.fire(Trigger.DismissOverlay) }) { Text("Unlock") }
            OutlinedButton(enabled = running, onClick = { TriggerBus.fire(Trigger.DoubleClap) }) { Text("Clap×2") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = running, onClick = { TriggerBus.fire(Trigger.DebugFindPhone) }) { Text("Find phone") }
            OutlinedButton(enabled = running, onClick = { TriggerBus.fire(Trigger.StopFindPhone) }) { Text("Stop find") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide log" else "Show log") }
            OutlinedButton(onClick = { SpikeLog.clear(ctx) }) { Text("Clear log") }
        }
        if (showLog) {
            Text(
                logText.ifBlank { "(empty)" },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
