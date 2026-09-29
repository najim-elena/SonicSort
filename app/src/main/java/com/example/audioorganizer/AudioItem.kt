package com.example.audioorganizer

import android.net.Uri

data class AudioItem(
    val uri: Uri,
    val id: Long,
    val displayName: String,
    val size: Long,
    val dateAdded: Long,
    var detectedLanguage: String = "Pending",
    var sha256: String? = null
)
