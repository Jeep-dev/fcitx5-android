/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Records 16 kHz mono 16-bit PCM from the microphone and packs it into a WAV file.
 */
class VoiceRecorder(private val maxDurationMs: Long) {

    /** Normalized input level of the latest buffer, in 0..1 */
    @Volatile
    var level = 0f
        private set

    @Volatile
    var elapsedMs = 0L
        private set

    @Volatile
    private var stopRequested = false

    /** Finish recording; [record] returns what has been recorded so far */
    fun stop() {
        stopRequested = true
    }

    /**
     * Blocks (on IO dispatcher) until [stop] is called, [maxDurationMs] is reached,
     * or the coroutine is cancelled.
     * @return WAV file content
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun record(): ByteArray = withContext(Dispatchers.IO) {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBufferSize <= 0) throw IOException("AudioRecord.getMinBufferSize: $minBufferSize")
        // read ~100ms each time
        val chunk = ByteArray(SAMPLE_RATE * BYTES_PER_SAMPLE / 10)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, CHANNEL, ENCODING,
            max(minBufferSize, chunk.size * 2)
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw IOException("AudioRecord failed to initialize")
        }
        val pcm = ByteArrayOutputStream()
        try {
            audioRecord.startRecording()
            val maxBytes = maxDurationMs * SAMPLE_RATE * BYTES_PER_SAMPLE / 1000
            while (!stopRequested && pcm.size() < maxBytes) {
                ensureActive()
                val n = audioRecord.read(chunk, 0, chunk.size)
                if (n < 0) throw IOException("AudioRecord.read: $n")
                pcm.write(chunk, 0, n)
                level = computeLevel(chunk, n)
                elapsedMs = pcm.size() * 1000L / (SAMPLE_RATE * BYTES_PER_SAMPLE)
            }
        } finally {
            runCatching { audioRecord.stop() }
            audioRecord.release()
            level = 0f
        }
        wrapWav(pcm.toByteArray())
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2

        /** Shorter recordings are most likely accidental */
        const val MIN_DURATION_MS = 300L

        private fun computeLevel(buf: ByteArray, len: Int): Float {
            val samples = len / BYTES_PER_SAMPLE
            if (samples == 0) return 0f
            val bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN)
            var sum = 0.0
            repeat(samples) {
                val s = bb.short.toDouble()
                sum += s * s
            }
            val rms = sqrt(sum / samples)
            if (rms < 1.0) return 0f
            // map -60dBFS..0dBFS to 0..1
            val db = 20 * log10(rms / Short.MAX_VALUE)
            return ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
        }

        private fun wrapWav(pcm: ByteArray): ByteArray {
            val byteRate = SAMPLE_RATE * BYTES_PER_SAMPLE
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray())
                putInt(36 + pcm.size)
                put("WAVE".toByteArray())
                put("fmt ".toByteArray())
                putInt(16) // PCM header size
                putShort(1) // PCM format
                putShort(1) // mono
                putInt(SAMPLE_RATE)
                putInt(byteRate)
                putShort(BYTES_PER_SAMPLE.toShort()) // block align
                putShort(16) // bits per sample
                put("data".toByteArray())
                putInt(pcm.size)
            }
            return header.array() + pcm
        }
    }
}
