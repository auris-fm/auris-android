package au.com.shiftyjelly.pocketcasts.repositories.playback

import android.content.Context
import au.com.shiftyjelly.pocketcasts.models.to.NoiseEnvironmentMode
import au.com.shiftyjelly.pocketcasts.repositories.R
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object PracticeNoiseSamples {
    data class Clip(
        val sampleRate: Int,
        val samples: FloatArray,
    )

    data class ModeSamples(
        val beds: List<Clip>,
        val speech: List<Clip>,
    )

    @Volatile
    private var cached: Map<NoiseEnvironmentMode, ModeSamples>? = null

    fun mode(context: Context, mode: NoiseEnvironmentMode): ModeSamples? {
        val local = cached ?: loadAll(context.applicationContext).also { cached = it }
        return local[mode]
    }

    private fun loadAll(context: Context): Map<NoiseEnvironmentMode, ModeSamples> {
        val coffeeBed = loadClip(context, R.raw.practice_noise_coffee_bed)
        val streetBed = loadClip(context, R.raw.practice_noise_street_bed)
        val meetingBed = loadClip(context, R.raw.practice_noise_meeting_bed)
        val crowdMale = loadClip(context, R.raw.practice_noise_speech_crowd_male)
        val pubMurmur = loadClip(context, R.raw.practice_noise_speech_pub_murmur)

        return mapOf(
            NoiseEnvironmentMode.COFFEE_SHOP to ModeSamples(
                beds = listOfNotNull(coffeeBed),
                speech = listOfNotNull(pubMurmur, crowdMale),
            ),
            NoiseEnvironmentMode.BUSY_STREET to ModeSamples(
                beds = listOfNotNull(streetBed),
                speech = listOfNotNull(crowdMale),
            ),
            NoiseEnvironmentMode.MEETING_ROOM to ModeSamples(
                beds = listOfNotNull(meetingBed),
                speech = listOfNotNull(crowdMale, pubMurmur),
            ),
        )
    }

    private fun loadClip(context: Context, rawId: Int): Clip? {
        return runCatching {
            context.resources.openRawResource(rawId).use { input ->
                val bytes = input.readBytes()
                parseWav(bytes)
            }
        }.getOrNull()
    }

    private fun parseWav(bytes: ByteArray): Clip {
        require(bytes.size > 44) { "WAV too small" }

        val riff = String(bytes, 0, 4)
        val wave = String(bytes, 8, 4)
        require(riff == "RIFF" && wave == "WAVE") { "Unsupported WAV header" }

        var offset = 12
        var sampleRate = 44_100
        var channels = 1
        var bitsPerSample = 16
        var pcmDataOffset = -1
        var pcmDataSize = -1

        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4)
            val chunkSize = leInt(bytes, offset + 4)
            val chunkDataStart = offset + 8
            if (chunkDataStart + chunkSize > bytes.size) break

            when (chunkId) {
                "fmt " -> {
                    val audioFormat = leShort(bytes, chunkDataStart).toInt() and 0xFFFF
                    channels = leShort(bytes, chunkDataStart + 2).toInt() and 0xFFFF
                    sampleRate = leInt(bytes, chunkDataStart + 4)
                    bitsPerSample = leShort(bytes, chunkDataStart + 14).toInt() and 0xFFFF
                    require(audioFormat == 1) { "Only PCM WAV is supported" }
                }

                "data" -> {
                    pcmDataOffset = chunkDataStart
                    pcmDataSize = chunkSize
                }
            }

            offset = chunkDataStart + chunkSize + (chunkSize and 1)
        }

        require(pcmDataOffset >= 0 && pcmDataSize > 0) { "WAV data chunk missing" }
        require(bitsPerSample == 16) { "Only 16-bit PCM WAV is supported" }
        require(channels >= 1) { "Invalid channel count" }

        val frameSizeBytes = channels * 2
        val frameCount = pcmDataSize / frameSizeBytes
        val sampleBuffer = ByteBuffer.wrap(bytes, pcmDataOffset, frameCount * frameSizeBytes)
            .order(ByteOrder.LITTLE_ENDIAN)

        val mono = FloatArray(frameCount)
        for (frame in 0 until frameCount) {
            var sum = 0
            for (channel in 0 until channels) {
                sum += sampleBuffer.short.toInt()
            }
            mono[frame] = (sum / channels.toFloat()) / Short.MAX_VALUE.toFloat()
        }

        return Clip(sampleRate = sampleRate, samples = mono)
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun leShort(bytes: ByteArray, offset: Int): Short {
        val value = (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        return value.toShort()
    }
}
