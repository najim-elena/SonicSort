package com.example.audioorganizer

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Centralized Gemini REST API client.
 *
 * Uses the Gemini REST API directly via OkHttp so we can send real inline
 * audio bytes — the older generativeai SDK (0.9.0) does not support audio
 * input correctly on Android.
 *
 * Audio is sent as base64-encoded inline data with the correct MIME type.
 * The model analyzes the actual audio content to detect spoken/sung language.
 */
object GeminiClient {

    // ── Centralized configuration — change model here only ──────────────────
    private const val MODEL = "gemini-1.5-flash"
    private const val BASE_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
    // ────────────────────────────────────────────────────────────────────────

    private const val TAG = "SonicSort.Gemini"

    // Max audio bytes to send inline (~15 seconds of 128 kbps MP3 ≈ 240 KB)
    // Keeps each request small and stays within free-tier token limits.
    const val MAX_AUDIO_BYTES = 300_000

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Sealed result returned for every API call.
     */
    sealed class GeminiResult {
        data class Success(val language: String, val confidence: Float) : GeminiResult()
        object NoVocals : GeminiResult()
        object UnknownLanguage : GeminiResult()
        data class Failure(val reason: String, val isRetryable: Boolean) : GeminiResult()
    }

    /**
     * Connectivity pre-flight check.  Sends a tiny text-only request.
     * Returns a human-readable status string.
     */
    fun testConnection(apiKey: String): String {
        if (apiKey.isBlank()) return "INVALID_API_KEY: Key is empty"
        return try {
            val payload = buildTextOnlyPayload("Reply with one word: ready")
            val response = execute(apiKey, payload)
            when (response.first) {
                200  -> "✓ Gemini connected (model: $MODEL)"
                401  -> "INVALID_API_KEY: Authentication failed (HTTP 401)"
                403  -> "INVALID_API_KEY: Access denied (HTTP 403)"
                404  -> "INVALID_MODEL: Model '$MODEL' not found (HTTP 404)"
                429  -> "QUOTA_EXCEEDED / RATE_LIMITED (HTTP 429)"
                else -> "API_ERROR: HTTP ${response.first} — ${response.second.take(200)}"
            }
        } catch (e: Exception) {
            "NETWORK_ERROR: ${e.message}"
        }
    }

    /**
     * Analyze audio bytes and return the detected language.
     *
     * @param audioBytes  Raw bytes of the audio sample (≤ MAX_AUDIO_BYTES)
     * @param mimeType    MIME type of the audio (audio/mpeg, audio/mp4, etc.)
     * @param apiKey      Gemini API key
     */
    fun detectLanguageFromAudio(
        audioBytes: ByteArray,
        mimeType: String,
        apiKey: String
    ): GeminiResult {
        if (apiKey.isBlank()) return GeminiResult.Failure("Invalid API key", false)

        val safeMime = sanitizeMimeType(mimeType)
        val b64 = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP)

        val payload = buildAudioPayload(b64, safeMime)

        Log.d(TAG, "Sending ${audioBytes.size} bytes of $safeMime to Gemini ($MODEL)")

        return try {
            val (code, body) = execute(apiKey, payload)
            parseResponse(code, body)
        } catch (e: Exception) {
            Log.e(TAG, "Network error: ${e.message}")
            GeminiResult.Failure("Network error: ${e.message?.take(80)}", true)
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun execute(apiKey: String, jsonPayload: String): Pair<Int, String> {
        val url = "$BASE_URL?key=$apiKey"
        val body = jsonPayload.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            // Never log the apiKey; it is only embedded in the URL at runtime
            .build()
        client.newCall(request).execute().use { resp ->
            val responseBody = resp.body?.string() ?: ""
            return Pair(resp.code, responseBody)
        }
    }

    private fun parseResponse(httpCode: Int, body: String): GeminiResult {
        Log.d(TAG, "HTTP $httpCode — response length: ${body.length}")

        return when (httpCode) {
            200 -> parseSuccessBody(body)
            401, 403 -> GeminiResult.Failure("Invalid API key (HTTP $httpCode)", false)
            404 -> GeminiResult.Failure("Model not found: $MODEL (HTTP 404)", false)
            429 -> GeminiResult.Failure("Rate limit / quota exceeded (HTTP 429)", true)
            500, 503 -> GeminiResult.Failure("Gemini server error (HTTP $httpCode)", true)
            else -> GeminiResult.Failure("API error HTTP $httpCode", true)
        }
    }

    private fun parseSuccessBody(body: String): GeminiResult {
        return try {
            val root = JSONObject(body)

            // Safety check — sometimes Gemini returns a candidates array that is empty
            val candidates = root.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                Log.w(TAG, "Empty candidates in response")
                return GeminiResult.Failure("Empty candidates (safety block?)", false)
            }

            val firstCandidate = candidates.getJSONObject(0)
            val content = firstCandidate.optJSONObject("content")
                ?: return GeminiResult.Failure("No content in candidate", false)
            val parts = content.optJSONArray("parts")
                ?: return GeminiResult.Failure("No parts in content", false)

            val rawText = buildString {
                for (i in 0 until parts.length()) {
                    append(parts.getJSONObject(i).optString("text", ""))
                }
            }.trim()

            Log.d(TAG, "Gemini raw text: $rawText")

            parseLanguageJson(rawText)
        } catch (e: Exception) {
            Log.e(TAG, "Response parse error: ${e.message}")
            GeminiResult.Failure("Parse error: ${e.message?.take(60)}", false)
        }
    }

    /**
     * Parses the strict JSON the prompt requests.
     * Handles JSON wrapped in ```json ... ``` code fences too.
     */
    private fun parseLanguageJson(rawText: String): GeminiResult {
        // Strip ```json ... ``` fences if present
        val cleaned = rawText
            .replace(Regex("```json\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("```\\s*"), "")
            .trim()

        return try {
            val json = JSONObject(cleaned)
            val language = json.optString("language", "").trim()
            val confidence = json.optDouble("confidence", 0.0).toFloat()

            when {
                language.equals("No Vocals", ignoreCase = true) ->
                    GeminiResult.NoVocals

                language.equals("Unknown", ignoreCase = true) || language.isBlank() ->
                    GeminiResult.UnknownLanguage

                else ->
                    GeminiResult.Success(normalizeLanguageName(language), confidence)
            }
        } catch (e: Exception) {
            // Gemini didn't return JSON — try to salvage a language name from plain text
            Log.w(TAG, "Not valid JSON; attempting plain-text salvage from: $cleaned")
            salvagePlainText(cleaned)
        }
    }

    /** Last-resort: if Gemini returned a plain word instead of JSON */
    private fun salvagePlainText(text: String): GeminiResult {
        val lower = text.lowercase().trim()
        return when {
            lower.contains("no vocal") || lower.contains("instrumental") ->
                GeminiResult.NoVocals
            lower.contains("unknown") || lower.isBlank() ->
                GeminiResult.UnknownLanguage
            else -> {
                // Take first recognizable word
                val word = text.split(Regex("[\\s,.]")).firstOrNull { it.length > 2 } ?: ""
                if (word.isNotBlank())
                    GeminiResult.Success(normalizeLanguageName(word), 0.5f)
                else
                    GeminiResult.UnknownLanguage
            }
        }
    }

    /** Normalize common alternative spellings / native-script names */
    private fun normalizeLanguageName(raw: String): String {
        val lower = raw.lowercase().trim()
        return when {
            lower in listOf("bangla", "bengali", "বাংলা", "বাংলাদেশি বাংলা") -> "Bengali"
            lower == "hindi" || lower == "हिन्दी" -> "Hindi"
            lower == "english" -> "English"
            lower == "urdu" || lower == "اردو" -> "Urdu"
            lower == "punjabi" || lower == "ਪੰਜਾਬੀ" -> "Punjabi"
            lower == "arabic" || lower == "العربية" -> "Arabic"
            lower == "tamil" || lower == "தமிழ்" -> "Tamil"
            lower == "telugu" || lower == "తెలుగు" -> "Telugu"
            lower == "nepali" || lower == "नेपाली" -> "Nepali"
            lower == "spanish" || lower == "español" -> "Spanish"
            lower == "korean" || lower == "한국어" -> "Korean"
            lower == "japanese" || lower == "日本語" -> "Japanese"
            lower == "chinese" || lower in listOf("mandarin", "cantonese", "中文") -> "Chinese"
            else -> raw.replaceFirstChar { it.uppercase() }  // preserve capitalization
        }
    }

    /** Map Android/MediaStore MIME types to those Gemini accepts */
    private fun sanitizeMimeType(mime: String): String {
        return when {
            mime.contains("mpeg") || mime.contains("mp3") -> "audio/mpeg"
            mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") -> "audio/mp4"
            mime.contains("ogg") -> "audio/ogg"
            mime.contains("wav") || mime.contains("wave") -> "audio/wav"
            mime.contains("flac") -> "audio/flac"
            mime.contains("opus") -> "audio/ogg"
            mime.contains("webm") -> "audio/webm"
            else -> "audio/mpeg"   // safe default
        }
    }

    private fun buildAudioPayload(base64Audio: String, mimeType: String): String {
        val prompt = """
Analyze the spoken or sung human vocals in this audio.

Identify the PRIMARY language being spoken or sung.

Return ONLY valid JSON with no extra text:

{"language": "Bengali", "confidence": 0.97}

Use standardized English language names. Examples:
Bengali, Hindi, English, Nepali, Punjabi, Urdu, Tamil, Telugu, Spanish, Arabic, Korean, Japanese, Chinese

If multiple languages exist, return the dominant one.

If there are NO intelligible human vocals (instrumental only):
{"language": "No Vocals", "confidence": 1.0}

If vocals exist but language genuinely cannot be determined:
{"language": "Unknown", "confidence": 0.0}

IMPORTANT: Analyze only the audio itself. Ignore any filename or metadata.
        """.trimIndent()

        return JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", mimeType)
                                put("data", base64Audio)
                            })
                        })
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.0)
                put("maxOutputTokens", 64)
            })
        }.toString()
    }

    private fun buildTextOnlyPayload(text: String): String {
        return JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", text) })
                    })
                })
            })
        }.toString()
    }
}
