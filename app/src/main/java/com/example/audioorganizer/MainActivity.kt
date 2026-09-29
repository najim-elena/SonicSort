package com.example.audioorganizer

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import com.example.audioorganizer.theme.AudioOrganizerTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) {
            viewModel.scanAudio()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_AUDIO))
        } else {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }

        setContent {
            AudioOrganizerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AudioOrganizerApp(viewModel)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Made by NAJIM",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}
@Composable
fun AudioOrganizerApp(viewModel: MainViewModel) {
    val status by viewModel.status.collectAsState()
    val audioFiles by viewModel.audioFiles.collectAsState()
    val duplicates by viewModel.duplicates.collectAsState()
    var apiKey by remember { mutableStateOf("") }
    var currentTab by remember { mutableStateOf(0) }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = "SonicSort", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "Status: $status", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(8.dp))

        Row {
            Button(onClick = { currentTab = 0 }) { Text("Main") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { currentTab = 1 }) { Text("Settings") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { currentTab = 2 }) { Text("Duplicates") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (currentTab) {
            0 -> {
                Button(onClick = { viewModel.scanAudio() }, modifier = Modifier.fillMaxWidth()) {
                    Text("1. Scan & Analyze")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { viewModel.detectLanguages() }, modifier = Modifier.fillMaxWidth()) {
                    Text("2. Detect Languages (Gemini)")
                }
                Spacer(modifier = Modifier.height(8.dp))
                
                Text("Total Songs: ${audioFiles.size}")
                val languages = audioFiles.groupBy { it.detectedLanguage }.mapValues { it.value.size }
                Text("Languages: $languages")
                
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(audioFiles.take(100)) { item ->
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(item.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text("Language: ${item.detectedLanguage}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            1 -> {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; viewModel.setApiKey(it) },
                    label = { Text("Gemini API Key") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Your API key is only kept in memory for this session.")
            }
            2 -> {
                Button(onClick = { viewModel.findDuplicates() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Find Exact Duplicates")
                }
                Text("Duplicate Groups: ${duplicates.size}")
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(duplicates) { group ->
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text("Group size: ${group.size} copies", style = MaterialTheme.typography.titleMedium)
                                group.forEachIndexed { index, item ->
                                    val action = if (index == 0) "KEEP" else "DELETE"
                                    Text("[$action] ${item.displayName}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Made by NAJIM",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}
