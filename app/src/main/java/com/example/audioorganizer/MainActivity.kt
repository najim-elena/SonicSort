package com.example.audioorganizer

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audioorganizer.theme.AudioOrganizerTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) {
            viewModel.scanAudio()
        } else {
            // Permission denied — still show UI but scanning won't work
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request appropriate audio permission for the device's Android version
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissionLauncher.launch(perms)

        setContent {
            AudioOrganizerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SonicSortApp(viewModel)
                }
            }
        }
    }
}

// ── Tab indices ───────────────────────────────────────────────────────────────
private const val TAB_MAIN      = 0
private const val TAB_SETTINGS  = 1
private const val TAB_DUPES     = 2

@Composable
fun SonicSortApp(viewModel: MainViewModel) {
    val status          by viewModel.status.collectAsState()
    val audioFiles      by viewModel.audioFiles.collectAsState()
    val duplicates      by viewModel.duplicates.collectAsState()
    val geminiTest      by viewModel.geminiTestResult.collectAsState()
    val progress        by viewModel.detectionProgress.collectAsState()

    var currentTab      by remember { mutableStateOf(TAB_MAIN) }
    var apiKey          by remember { mutableStateOf(viewModel.getApiKey()) }
    var showKey         by remember { mutableStateOf(false) }

    Column(modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 16.dp, vertical = 8.dp)) {

        // ── Header ────────────────────────────────────────────────────────────
        Text(
            text = "SonicSort",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Audio Language Organizer",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))

        // ── Tab bar ────────────────────────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabButton("Main",      currentTab == TAB_MAIN)      { currentTab = TAB_MAIN }
            TabButton("Settings",  currentTab == TAB_SETTINGS)  { currentTab = TAB_SETTINGS }
            TabButton("Duplicates",currentTab == TAB_DUPES)     { currentTab = TAB_DUPES }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        // ── Tab content ───────────────────────────────────────────────────────
        when (currentTab) {

            // ──── MAIN TAB ──────────────────────────────────────────────────
            TAB_MAIN -> {
                // Status card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(
                        text = status,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Action buttons
                Button(
                    onClick = { viewModel.scanAudio() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("1. Scan & Analyze") }

                Spacer(modifier = Modifier.height(6.dp))

                Button(
                    onClick = { viewModel.detectLanguages() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("2. Detect Languages (Gemini)") }

                // Progress bar while detecting
                if (progress.total > 0 && progress.done < progress.total) {
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${progress.done} / ${progress.total} — ${progress.percent}%",
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                // Cancel / Retry row
                if (progress.total > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.cancelDetection() }) {
                            Text("Cancel", fontSize = 12.sp)
                        }
                        if (progress.failed > 0) {
                            OutlinedButton(onClick = { viewModel.retryFailed() }) {
                                Text("Retry ${progress.failed} Failed", fontSize = 12.sp)
                            }
                        }
                        OutlinedButton(onClick = { viewModel.reanalyzeAll() }) {
                            Text("Re-analyze All", fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Summary counts
                if (audioFiles.isNotEmpty()) {
                    val byLang = audioFiles
                        .filter { it.detectionStatus == DetectionStatus.DETECTED }
                        .groupBy { it.detectedLanguage }
                        .mapValues { it.value.size }
                        .entries
                        .sortedByDescending { it.value }

                    SummaryRow("Total songs",  audioFiles.size.toString())
                    SummaryRow("Detected",     progress.detected.toString())
                    SummaryRow("No Vocals",    progress.noVocals.toString())
                    SummaryRow("Unknown",      progress.unknown.toString())
                    SummaryRow("Failed",       progress.failed.toString())
                    SummaryRow("Pending",      progress.pending.toString())

                    if (byLang.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Languages detected:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        byLang.forEach { (lang, count) ->
                            SummaryRow("  $lang", count.toString())
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Song list
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(audioFiles) { item ->
                        AudioItemRow(item)
                    }
                }
            }

            // ──── SETTINGS TAB ─────────────────────────────────────────────
            TAB_SETTINGS -> {
                Text("Gemini API Key", fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        viewModel.setApiKey(it)
                    },
                    label = { Text("Paste your API key") },
                    visualTransformation = if (showKey) VisualTransformation.None
                                          else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "Hide Key" else "Show Key", fontSize = 12.sp)
                    }
                    Button(onClick = { viewModel.testGeminiConnection() }) {
                        Text("Test API", fontSize = 12.sp)
                    }
                }

                if (geminiTest.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val isOk = geminiTest.startsWith("✓")
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isOk) Color(0xFFD4EDDA) else Color(0xFFFFD6D6)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = geminiTest,
                            modifier = Modifier.padding(12.dp),
                            color = if (isOk) Color(0xFF155724) else Color(0xFF721C24),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                Text("Privacy Notice", fontWeight = FontWeight.SemiBold)
                Text(
                    "When Detect Languages is active, short audio samples (~15 sec) are " +
                    "sent to Google's Gemini API for vocal language analysis. No files are " +
                    "stored permanently. Your API key is kept in memory only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ──── DUPLICATES TAB ───────────────────────────────────────────
            TAB_DUPES -> {
                Button(
                    onClick = { viewModel.findDuplicates() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Scan for Exact Duplicates (SHA-256)") }

                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(
                        text = status,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (duplicates.isNotEmpty()) {
                    SummaryRow("Duplicate groups", duplicates.size.toString())
                    val wasted = duplicates.sumOf { g -> g.drop(1).sumOf { it.size } }
                    SummaryRow("Space recoverable", formatBytes(wasted))

                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.deleteDuplicates() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Delete Duplicates (keep 1 per group)") }

                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(duplicates) { group ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        "SHA-256 verified · ${group.size} copies · ${formatBytes(group.first().size)} each",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    group.forEachIndexed { i, item ->
                                        val label = if (i == 0) "✓ KEEP" else "✗ DELETE"
                                        val color  = if (i == 0) Color(0xFF155724) else Color(0xFF721C24)
                                        Text(
                                            "[$label] ${item.displayName}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = color
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        // ── Footer watermark ──────────────────────────────────────────────────
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Made by NAJIM",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

// ── Small reusable composables ────────────────────────────────────────────────

@Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label, fontSize = 12.sp) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label, fontSize = 12.sp) }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AudioItemRow(item: AudioItem) {
    val statusColor = when (item.detectionStatus) {
        DetectionStatus.DETECTED    -> Color(0xFF155724)
        DetectionStatus.NO_VOCALS   -> Color(0xFF004085)
        DetectionStatus.UNKNOWN     -> Color(0xFF856404)
        DetectionStatus.FAILED      -> Color(0xFF721C24)
        DetectionStatus.PROCESSING  -> MaterialTheme.colorScheme.primary
        DetectionStatus.PENDING     -> Color.Gray
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                if (item.duration > 0) {
                    Text(
                        formatDuration(item.duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = item.displayStatus,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000     -> "%.0f KB".format(bytes / 1_000.0)
    else               -> "$bytes B"
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
