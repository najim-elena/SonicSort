import os

code_viewmodel = """package com.example.audioorganizer

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _audioFiles = MutableStateFlow<List<AudioItem>>(emptyList())
    val audioFiles: StateFlow<List<AudioItem>> = _audioFiles

    private val _duplicates = MutableStateFlow<List<List<AudioItem>>>(emptyList())
    val duplicates: StateFlow<List<List<AudioItem>>> = _duplicates

    private val _status = MutableStateFlow("Ready")
    val status: StateFlow<String> = _status

    private var apiKey = ""

    fun setApiKey(key: String) {
        apiKey = key
    }

    fun scanAudio() {
        viewModelScope.launch {
            _status.value = "Scanning Audio..."
            val list = mutableListOf<AudioItem>()
            withContext(Dispatchers.IO) {
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_ADDED
                )
                
                val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                val cursor = getApplication<Application>().contentResolver.query(
                    collection, projection, null, null, null
                )
                
                cursor?.use {
                    val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val nameCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val sizeCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                    val dateCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                    
                    while (it.moveToNext()) {
                        val id = it.getLong(idCol)
                        val uri = ContentUris.withAppendedId(collection, id)
                        list.add(AudioItem(
                            uri = uri,
                            id = id,
                            displayName = it.getString(nameCol) ?: "Unknown",
                            size = it.getLong(sizeCol),
                            dateAdded = it.getLong(dateCol)
                        ))
                    }
                }
            }
            _audioFiles.value = list
            _status.value = "Found  audio files."
        }
    }

    fun detectLanguages() {
        if (apiKey.isEmpty()) {
            _status.value = "Please set Gemini API key in settings."
            return
        }
        viewModelScope.launch {
            val model = GenerativeModel("gemini-1.5-flash", apiKey)
            var count = 0
            val total = _audioFiles.value.size
            for (file in _audioFiles.value) {
                if (file.detectedLanguage == "Pending" || file.detectedLanguage == "Unknown") {
                    _status.value = "Detecting language... /\nCurrent: "
                    val prompt = "Determine the language of a song with filename: . Reply ONLY with the language name like English, Bangla, Hindi, Unknown, Mixed."
                    try {
                        val response = withContext(Dispatchers.IO) { model.generateContent(prompt) }
                        val text = response.text?.trim()?.replace(Regex("[^a-zA-Z]"), "") ?: "Unknown"
                        file.detectedLanguage = text
                    } catch (e: Exception) {
                        Log.e("AudioOrganizer", "Gemini error", e)
                        file.detectedLanguage = "Unknown"
                    }
                }
                count++
            }
            _status.value = "Language detection complete."
            _audioFiles.value = ArrayList(_audioFiles.value) // force recompose
        }
    }

    fun findDuplicates() {
        viewModelScope.launch {
            _status.value = "Finding duplicates (Hashing)..."
            withContext(Dispatchers.IO) {
                val sizeGroups = _audioFiles.value.groupBy { it.size }.filter { it.value.size > 1 }
                val duplicateGroups = mutableListOf<List<AudioItem>>()
                
                var checked = 0
                val totalToCheck = sizeGroups.values.sumOf { it.size }
                
                for ((_, group) in sizeGroups) {
                    val hashGroups = mutableMapOf<String, MutableList<AudioItem>>()
                    for (item in group) {
                        _status.value = "Hashing file /\n"
                        if (item.sha256 == null) {
                            item.sha256 = hashFile(item.uri)
                        }
                        item.sha256?.let { hash ->
                            if (!hashGroups.containsKey(hash)) hashGroups[hash] = mutableListOf()
                            hashGroups[hash]!!.add(item)
                        }
                        checked++
                    }
                    duplicateGroups.addAll(hashGroups.values.filter { it.size > 1 })
                }
                _duplicates.value = duplicateGroups
            }
            _status.value = "Found  exact duplicate groups."
        }
    }

    private fun hashFile(uri: Uri): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val stream = getApplication<Application>().contentResolver.openInputStream(uri)
            val buffer = ByteArray(8192)
            var read: Int
            while (stream?.read(buffer).also { read = it ?: -1 } != -1) {
                digest.update(buffer, 0, read)
            }
            stream?.close()
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }
}
"""
os.makedirs("app/src/main/java/com/example/audioorganizer", exist_ok=True)
with open("app/src/main/java/com/example/audioorganizer/MainViewModel.kt", "w") as f:
    f.write(code_viewmodel)
