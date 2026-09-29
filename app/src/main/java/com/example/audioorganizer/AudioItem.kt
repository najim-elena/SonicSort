package com.example.audioorganizer

import android.net.Uri

// Detection status - strictly separated
enum class DetectionStatus {
    PENDING,       // Not yet analyzed
    PROCESSING,    // Currently being sent to Gemini
    DETECTED,      // Gemini successfully identified a language
    NO_VOCALS,     // Gemini confirmed: no human vocals present
    UNKNOWN,       // Gemini analyzed successfully but could not identify language
    FAILED         // Could not complete analysis (network error, API error, etc.)
}

data class AudioItem(
    val uri: Uri,
    val id: Long,
    val displayName: String,
    val size: Long,
    val dateAdded: Long,
    val mimeType: String = "",
    val duration: Long = 0L,
    var detectedLanguage: String = "",
    var confidence: Float = 0f,
    var detectionStatus: DetectionStatus = DetectionStatus.PENDING,
    var failureReason: String = "",   // Only set when status == FAILED
    var sha256: String? = null,
    var analysisTimestamp: Long = 0L
) {
    /** Human-readable label for display in the UI. */
    val displayStatus: String get() = when (detectionStatus) {
        DetectionStatus.PENDING     -> "Pending"
        DetectionStatus.PROCESSING  -> "Processing..."
        DetectionStatus.DETECTED    -> detectedLanguage
        DetectionStatus.NO_VOCALS   -> "No Vocals"
        DetectionStatus.UNKNOWN     -> "Unknown"
        DetectionStatus.FAILED      -> "Failed: $failureReason"
    }
}
