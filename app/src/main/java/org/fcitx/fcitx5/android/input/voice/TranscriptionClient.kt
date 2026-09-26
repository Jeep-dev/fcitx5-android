/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Client for OpenAI-compatible `/v1/audio/transcriptions` endpoints
 * (OpenAI Whisper, Groq, SiliconFlow, self-hosted faster-whisper-server, ...)
 */
class TranscriptionClient(
    private val url: String,
    private val apiKey: String,
    private val model: String,
    private val language: String,
    private val prompt: String
) {

    class ApiException(message: String) : IOException(message)

    suspend fun transcribe(wav: ByteArray): String = withContext(Dispatchers.IO) {
        val boundary = "fcitx5-" + UUID.randomUUID().toString()
        val body = buildMultipartBody(boundary, wav)
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.useCaches = false
            conn.setFixedLengthStreamingMode(body.size)
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw ApiException("HTTP $code: ${extractErrorMessage(response)}")
            }
            parseText(response)
        } finally {
            conn.disconnect()
        }
    }

    private fun buildMultipartBody(boundary: String, wav: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(wav.size + 1024)
        fun write(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        fun field(name: String, value: String) {
            write("--$boundary\r\n")
            write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
            write(value)
            write("\r\n")
        }
        field("model", model.trim())
        field("response_format", "json")
        if (language.isNotBlank()) field("language", language.trim())
        if (prompt.isNotBlank()) field("prompt", prompt)
        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n")
        write("Content-Type: audio/wav\r\n\r\n")
        out.write(wav)
        write("\r\n--$boundary--\r\n")
        return out.toByteArray()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000

        private fun parseText(response: String): String {
            val obj = runCatching { Json.parseToJsonElement(response) as? JsonObject }.getOrNull()
                ?: throw ApiException("Unexpected response: ${response.take(200)}")
            val text = obj["text"]?.jsonPrimitive?.content
                ?: throw ApiException("Unexpected response: ${response.take(200)}")
            return text.trim()
        }

        private fun extractErrorMessage(response: String): String {
            val obj = runCatching { Json.parseToJsonElement(response) as? JsonObject }.getOrNull()
            val message = obj?.let {
                // {"error": {"message": "..."}} or {"error": "..."} or {"message": "..."}
                runCatching { (it["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.content }.getOrNull()
                    ?: runCatching { it["error"]?.jsonPrimitive?.content }.getOrNull()
                    ?: runCatching { it["message"]?.jsonPrimitive?.content }.getOrNull()
            }
            return message ?: response.take(200)
        }
    }
}
