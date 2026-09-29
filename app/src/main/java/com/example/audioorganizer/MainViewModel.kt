package com.example.audioorganizer

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // ── Public state ─────────────────────────────────────────────────────────
    private val _audioFiles = MutableStateFlow<List<AudioItem>>(emptyList())
    val audioFiles: StateFlow<List<AudioItem>> = _audioFiles

    private val _duplicates = MutableStateFlow<List<List<AudioItem>>>(emptyList())
    val duplicates: StateFlow<List<List<AudioItem>>> = _duplicates

    private val _status = MutableStateFlow("Ready — tap Scan & Analyze to begin")
    val status: StateFlow<String> = _status

    private val _geminiTestResult = MutableStateFlow("")
    val geminiTestResult: StateFlow<String> = _geminiTestResult

    // Detection progress counters
    private val _detectionProgress = MutableStateFlow(DetectionProgress())
    val detectionProgress: StateFlow<DetectionProgress> = _detectionProgress

    // ── Private ───────────────────────────────────────────────────────────────
    private var apiKey = ""
    private var detectionJob: Job? = null

    /** Concurrency cap: free-tier Gemini allows ~2 RPM on Flash — keep 1 at a time */
    private val geminiSemaphore = Semaphore(1)

    private val TAG = "SonicSort.VM"

    // Simple in-memory cache: mediaStoreId → AudioItem (survives re-scans in same session)
    private val languageCache = mutableMapOf<Long, AudioItem>()

    fun setApiKey(key: String) {
        apiKey = key.trim()
    }

    fun getApiKey(): String = apiKey

    // ── Gemini connectivity test ─────────────────────────────────────────────
    fun testGeminiConnection() {
        viewModelScope.launch {
            _geminiTestResult.value = "Testing..."
            val result = withContext(Dispatchers.IO) {
                GeminiClient.testConnection(apiKey)
            }
            _geminiTestResult.value = result
        }
    }

    // ── Audio scanning ───────────────────────────────────────────────────────
    fun scanAudio() {
        viewModelScope.launch {
            _status.value = "Scanning device audio..."
            val list = mutableListOf<AudioItem>()

            withContext(Dispatchers.IO) {
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.MIME_TYPE,
                    MediaStore.Audio.Media.DURATION
                )

                val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                val cursor = getApplication<Application>().contentResolver.query(
                    collection, projection, null, null,
                    "${MediaStore.Audio.Media.DATE_ADDED} DESC"
                )

                cursor?.use { c ->
                    val idCol   = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                    val dateCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                    val mimeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                    val durCol  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                    while (c.moveToNext()) {
                        val id  = c.getLong(idCol)
                        val uri = ContentUris.withAppendedId(collection, id)

                        // Restore from cache if available
                        val cached = languageCache[id]
                        val item = AudioItem(
                            uri         = uri,
                            id          = id,
                            displayName = c.getString(nameCol) ?: "Unknown",
                            size        = c.getLong(sizeCol),
                            dateAdded   = c.getLong(dateCol),
                            mimeType    = c.getString(mimeCol) ?: "audio/mpeg",
                            duration    = c.getLong(durCol)
                        ).also { fresh ->
                            if (cached != null &&
                                cached.detectionStatus in listOf(
                                    DetectionStatus.DETECTED,
                                    DetectionStatus.NO_VOCALS
                                )
                            ) {
                                fresh.detectedLanguage  = cached.detectedLanguage
                                fresh.confidence        = cached.confidence
                                fresh.detectionStatus   = cached.detectionStatus
                                fresh.analysisTimestamp = cached.analysisTimestamp
                            }
                        }
                        list.add(item)
                    }
                }
            }

            _audioFiles.value = list
            _status.value = "Found ${list.size} audio files. Set your Gemini API key and tap Detect Languages."
            Log.i(TAG, "Scan complete: ${list.size} files")
        }
    }

    // ── Language detection ───────────────────────────────────────────────────
    fun detectLanguages() {
        if (apiKey.isEmpty()) {
            _status.value = "⚠ Please enter your Gemini API key in Settings first."
            return
        }

        // Cancel any in-progress detection
        detectionJob?.cancel()

        detectionJob = viewModelScope.launch {
            // ── Step 1: Pre-flight Gemini check ─────────────────────────────
            _status.value = "Checking Gemini connection..."
            val testResult = withContext(Dispatchers.IO) { GeminiClient.testConnection(apiKey) }

            if (!testResult.startsWith("✓")) {
                _status.value = "❌ Cannot start detection:\n$testResult"
                Log.e(TAG, "Pre-flight failed: $testResult")
                return@launch
            }

            Log.i(TAG, "Pre-flight OK: $testResult")

            // ── Step 2: Determine which files need analysis ──────────────────
            val toProcess = _audioFiles.value.filter { item ->
                item.detectionStatus in listOf(
                    DetectionStatus.PENDING,
                    DetectionStatus.FAILED,
                    DetectionStatus.UNKNOWN
                )
            }

            if (toProcess.isEmpty()) {
                _status.value = "All songs already analyzed. Use Re-analyze All to reprocess."
                return@launch
            }

            val total = toProcess.size
            var done  = 0
            var permanentFailure = false

            _detectionProgress.value = DetectionProgress(total = total)

            // ── Step 3: Process queue with controlled concurrency ────────────
            for (item in toProcess) {
                if (permanentFailure) break

                geminiSemaphore.withPermit {
                    updateItemStatus(item, DetectionStatus.PROCESSING)
                    done++

                    Log.d(TAG, "[$done/$total] Processing: ${item.displayName}")
                    Log.d(TAG, "  URI: ${item.uri}  MIME: ${item.mimeType}  Size: ${item.size}  Duration: ${item.duration}ms")

                    val result = withContext(Dispatchers.IO) {
                        processOneFile(item)
                    }

                    applyResult(item, result)

                    // Update progress state
                    _detectionProgress.update { it.recount(_audioFiles.value) }

                    // Rate-limit: ~1 request per 2 seconds (safe for free tier)
                    // Gemini Flash free tier: 15 RPM = 1 request per 4 seconds
                    delay(4_000)
                }

                // If we hit a permanent error (bad API key, no quota), stop early
                if (result_is_permanent_failure(item)) {
                    permanentFailure = true
                    _status.value = "❌ Detection stopped: ${item.failureReason}\nCompleted: $done / $total"
                }
            }

            val final = _detectionProgress.value
            _status.value = buildFinalStatus(final)
        }
    }

    fun cancelDetection() {
        detectionJob?.cancel()
        _status.value = "Detection cancelled."
    }

    fun reanalyzeAll() {
        languageCache.clear()
        val reset = _audioFiles.value.map { it.copy(
            detectionStatus = DetectionStatus.PENDING,
            detectedLanguage = "",
            confidence = 0f,
            failureReason = ""
        )}
        _audioFiles.value = reset
        detectLanguages()
    }

    fun retryFailed() {
        val updated = _audioFiles.value.map { item ->
            if (item.detectionStatus == DetectionStatus.FAILED) {
                item.copy(detectionStatus = DetectionStatus.PENDING, failureReason = "")
            } else item
        }
        _audioFiles.value = updated
        detectLanguages()
    }

    // ── Core per-file processing ─────────────────────────────────────────────
    private fun processOneFile(item: AudioItem): GeminiClient.GeminiResult {
        val ctx = getApplication<Application>()

        // Read audio bytes — use a short sample to keep request sizes small
        val (audioBytes, mimeType) = try {
            readAudioSample(ctx, item)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot read audio for ${item.displayName}: ${e.message}")
            return GeminiClient.GeminiResult.Failure("Unable to read audio: ${e.message?.take(60)}", false)
        }

        if (audioBytes == null || audioBytes.isEmpty()) {
            Log.w(TAG, "Empty audio bytes for ${item.displayName}")
            return GeminiClient.GeminiResult.Failure("Audio stream was empty", false)
        }

        Log.d(TAG, "  Audio readable: true  Sample size: ${audioBytes.size} bytes  MIME: $mimeType")

        // Retry up to 3 times with exponential backoff for retryable errors
        var lastResult: GeminiClient.GeminiResult = GeminiClient.GeminiResult.Failure("Not attempted", true)
        for (attempt in 1..3) {
            lastResult = GeminiClient.detectLanguageFromAudio(audioBytes, mimeType, apiKey)

            if (lastResult !is GeminiClient.GeminiResult.Failure ||
                !(lastResult as GeminiClient.GeminiResult.Failure).isRetryable) {
                break
            }

            val backoff = attempt * 10_000L
            Log.w(TAG, "  Retry $attempt in ${backoff/1000}s — ${(lastResult as GeminiClient.GeminiResult.Failure).reason}")
            Thread.sleep(backoff)
        }

        return lastResult
    }

    /**
     * Reads up to [GeminiClient.MAX_AUDIO_BYTES] bytes from the beginning of
     * the audio file via ContentResolver (works with content:// URIs).
     *
     * For longer songs, reading the first ~15 seconds of audio gives Gemini
     * enough signal to identify the vocal language.
     */
    private fun readAudioSample(ctx: Context, item: AudioItem): Pair<ByteArray?, String> {
        val stream = ctx.contentResolver.openInputStream(item.uri)
            ?: return Pair(null, item.mimeType)

        return stream.use { input ->
            val buffer = ByteArray(GeminiClient.MAX_AUDIO_BYTES)
            var totalRead = 0
            var read: Int
            while (totalRead < buffer.size) {
                read = input.read(buffer, totalRead, buffer.size - totalRead)
                if (read == -1) break
                totalRead += read
            }
            Pair(buffer.copyOf(totalRead), item.mimeType)
        }
    }

    // ── State management helpers ──────────────────────────────────────────────
    private fun updateItemStatus(item: AudioItem, status: DetectionStatus) {
        val updated = _audioFiles.value.map {
            if (it.id == item.id) it.copy(detectionStatus = status) else it
        }
        _audioFiles.value = updated
    }

    private fun applyResult(item: AudioItem, result: GeminiClient.GeminiResult) {
        val updatedItem = when (result) {
            is GeminiClient.GeminiResult.Success -> {
                Log.i(TAG, "  ✓ ${item.displayName} → ${result.language} (${result.confidence})")
                item.copy(
                    detectedLanguage  = result.language,
                    confidence        = result.confidence,
                    detectionStatus   = DetectionStatus.DETECTED,
                    failureReason     = "",
                    analysisTimestamp = System.currentTimeMillis()
                )
            }
            is GeminiClient.GeminiResult.NoVocals -> {
                Log.i(TAG, "  ♪ ${item.displayName} → No Vocals")
                item.copy(
                    detectedLanguage  = "No Vocals",
                    confidence        = 1f,
                    detectionStatus   = DetectionStatus.NO_VOCALS,
                    failureReason     = "",
                    analysisTimestamp = System.currentTimeMillis()
                )
            }
            is GeminiClient.GeminiResult.UnknownLanguage -> {
                Log.i(TAG, "  ? ${item.displayName} → Unknown")
                item.copy(
                    detectedLanguage  = "Unknown",
                    confidence        = 0f,
                    detectionStatus   = DetectionStatus.UNKNOWN,
                    failureReason     = "",
                    analysisTimestamp = System.currentTimeMillis()
                )
            }
            is GeminiClient.GeminiResult.Failure -> {
                Log.w(TAG, "  ✗ ${item.displayName} → FAILED: ${result.reason}")
                item.copy(
                    detectionStatus = DetectionStatus.FAILED,
                    failureReason   = result.reason
                )
            }
        }

        // Persist to in-memory cache
        languageCache[item.id] = updatedItem

        // Emit updated list
        _audioFiles.value = _audioFiles.value.map {
            if (it.id == item.id) updatedItem else it
        }
    }

    private fun result_is_permanent_failure(item: AudioItem): Boolean {
        if (item.detectionStatus != DetectionStatus.FAILED) return false
        val reason = item.failureReason.lowercase()
        return reason.contains("invalid api key") ||
               reason.contains("authentication") ||
               reason.contains("access denied")
    }

    private fun buildFinalStatus(p: DetectionProgress): String {
        val sb = StringBuilder("Detection complete\n")
        if (p.detected   > 0) sb.appendLine("Detected: ${p.detected}")
        if (p.noVocals   > 0) sb.appendLine("No Vocals: ${p.noVocals}")
        if (p.unknown    > 0) sb.appendLine("Unknown: ${p.unknown}")
        if (p.failed     > 0) sb.appendLine("Failed: ${p.failed} (tap Retry Failed)")
        return sb.toString().trim()
    }

    // ── Duplicate detection ───────────────────────────────────────────────────
    fun findDuplicates() {
        viewModelScope.launch {
            _status.value = "Finding duplicates (SHA-256)..."
            withContext(Dispatchers.IO) {
                val sizeGroups = _audioFiles.value.groupBy { it.size }.filter { it.value.size > 1 }
                val duplicateGroups = mutableListOf<List<AudioItem>>()
                var checked = 0
                val total = sizeGroups.values.sumOf { it.size }

                for ((_, group) in sizeGroups) {
                    val hashGroups = mutableMapOf<String, MutableList<AudioItem>>()
                    for (item in group) {
                        _status.value = "Hashing $checked/$total — ${item.displayName}"
                        if (item.sha256 == null) {
                            item.sha256 = hashFile(item.uri)
                        }
                        item.sha256?.let { hash ->
                            hashGroups.getOrPut(hash) { mutableListOf() }.add(item)
                        }
                        checked++
                    }
                    duplicateGroups.addAll(hashGroups.values.filter { it.size > 1 })
                }
                _duplicates.value = duplicateGroups
            }
            _status.value = "Found ${_duplicates.value.size} exact duplicate group(s) (SHA-256 verified)."
        }
    }

    fun deleteDuplicates() {
        viewModelScope.launch {
            val urisToDelete = _duplicates.value.flatMap { group -> group.drop(1).map { it.uri } }
            if (urisToDelete.isEmpty()) {
                _status.value = "No duplicates to delete."
                return@launch
            }
            // Android 11+ requires MediaStore.createDeleteRequest — signal to Activity
            _status.value = "Ready to delete ${urisToDelete.size} duplicate file(s). OS confirmation required."
        }
    }

    private fun hashFile(uri: Uri): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val stream = getApplication<Application>().contentResolver.openInputStream(uri) ?: return null
            val buffer = ByteArray(16_384)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
            stream.close()
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Hash error for $uri: ${e.message}")
            null
        }
    }
}

// ── Progress data class ───────────────────────────────────────────────────────
data class DetectionProgress(
    val total:      Int = 0,
    val processing: Int = 0,
    val detected:   Int = 0,
    val noVocals:   Int = 0,
    val unknown:    Int = 0,
    val failed:     Int = 0,
    val pending:    Int = 0
) {
    val done: Int get() = detected + noVocals + unknown + failed
    val percent: Int get() = if (total == 0) 0 else ((done.toFloat() / total) * 100).toInt()

    fun recount(items: List<AudioItem>) = DetectionProgress(
        total      = total,
        processing = items.count { it.detectionStatus == DetectionStatus.PROCESSING },
        detected   = items.count { it.detectionStatus == DetectionStatus.DETECTED },
        noVocals   = items.count { it.detectionStatus == DetectionStatus.NO_VOCALS },
        unknown    = items.count { it.detectionStatus == DetectionStatus.UNKNOWN },
        failed     = items.count { it.detectionStatus == DetectionStatus.FAILED },
        pending    = items.count { it.detectionStatus == DetectionStatus.PENDING }
    )
}
