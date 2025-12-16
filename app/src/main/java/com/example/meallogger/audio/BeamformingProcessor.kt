package com.example.meallogger.audio

import android.util.Log
import kotlin.math.min

/**
 * Beamforming processor for THINKLET's 6-microphone array (5ch + 1 empty)
 * Implements delay-and-sum beamforming to enhance wearer's voice
 *
 * Processes 6-channel 48kHz PCM data from THINKLET SDK and outputs mono 48kHz
 * (to be downsampled to 16kHz for Vosk)
 */
class BeamformingProcessor {

    companion object {
        private const val TAG = "BeamformingProcessor"
        private const val SPEED_OF_SOUND = 343.0 // m/s

        // THINKLET microphone array geometry (approximate positions in meters)
        // Assuming microphones are positioned around glasses frame
        private val MIC_POSITIONS = arrayOf(
            doubleArrayOf(-0.05, 0.0, 0.0),   // Left front
            doubleArrayOf(-0.03, 0.01, 0.0),  // Left side
            doubleArrayOf(-0.03, -0.01, 0.0), // Left back
            doubleArrayOf(0.05, 0.0, 0.0),    // Right front
            doubleArrayOf(0.03, 0.01, 0.0),   // Right side
            doubleArrayOf(0.03, -0.01, 0.0)   // Right back
        )

        // Target direction: wearer's mouth (in front and below)
        private val TARGET_DIRECTION = doubleArrayOf(0.0, -0.1, 0.15) // x, y, z
    }

    private val numChannels = 6
    private var sampleRate = 16000

    // Delay buffers for each microphone
    private val delayBuffers = Array(numChannels) { mutableListOf<Short>() }
    private val delaySamples = IntArray(numChannels)

    init {
        calculateDelays()
    }

    /**
     * Calculate time delays for each microphone based on target direction
     */
    private fun calculateDelays() {
        // Normalize target direction
        val targetMag = Math.sqrt(
            TARGET_DIRECTION[0] * TARGET_DIRECTION[0] +
            TARGET_DIRECTION[1] * TARGET_DIRECTION[1] +
            TARGET_DIRECTION[2] * TARGET_DIRECTION[2]
        )
        val targetUnit = doubleArrayOf(
            TARGET_DIRECTION[0] / targetMag,
            TARGET_DIRECTION[1] / targetMag,
            TARGET_DIRECTION[2] / targetMag
        )

        // Calculate delay for each microphone relative to center
        val delays = DoubleArray(numChannels)
        for (i in 0 until numChannels) {
            // Dot product with target direction
            val projection =
                MIC_POSITIONS[i][0] * targetUnit[0] +
                MIC_POSITIONS[i][1] * targetUnit[1] +
                MIC_POSITIONS[i][2] * targetUnit[2]

            // Time delay in seconds
            delays[i] = projection / SPEED_OF_SOUND
        }

        // Find minimum delay (reference)
        val minDelay = delays.minOrNull() ?: 0.0

        // Convert to sample delays (relative to minimum)
        for (i in 0 until numChannels) {
            delaySamples[i] = ((delays[i] - minDelay) * sampleRate).toInt()
        }

        Log.d(TAG, "Beamforming delays (samples): ${delaySamples.joinToString()}")
    }

    /**
     * Process multi-channel PCM audio data with beamforming
     * @param multiChannelData Interleaved 6-channel PCM data (16-bit shorts as bytes)
     * @return Mono beamformed PCM data
     */
    fun processMultiChannel(multiChannelData: ByteArray): ByteArray {
        if (multiChannelData.size % (numChannels * 2) != 0) {
            Log.w(TAG, "Invalid multi-channel data size: ${multiChannelData.size}")
            return multiChannelData
        }

        val numFrames = multiChannelData.size / (numChannels * 2)
        val outputData = ByteArray(numFrames * 2)

        // Deinterleave channels
        val channels = Array(numChannels) { ShortArray(numFrames) }
        for (frame in 0 until numFrames) {
            for (ch in 0 until numChannels) {
                val idx = (frame * numChannels + ch) * 2
                val sample = ((multiChannelData[idx + 1].toInt() shl 8) or
                             (multiChannelData[idx].toInt() and 0xFF)).toShort()
                channels[ch][frame] = sample
            }
        }

        // Apply beamforming (delay-and-sum)
        for (frame in 0 until numFrames) {
            var sum = 0L
            var count = 0

            for (ch in 0 until numChannels) {
                // Add current sample to delay buffer
                delayBuffers[ch].add(channels[ch][frame])

                // Check if we have enough samples in buffer
                if (delayBuffers[ch].size > delaySamples[ch]) {
                    // Get delayed sample
                    val delayedSample = delayBuffers[ch][0]
                    delayBuffers[ch].removeAt(0)

                    sum += delayedSample.toLong()
                    count++
                }
            }

            // Average and convert back to short
            val output = if (count > 0) {
                (sum / count).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            } else {
                0.toShort()
            }

            // Write to output buffer (mono)
            val outIdx = frame * 2
            outputData[outIdx] = (output.toInt() and 0xFF).toByte()
            outputData[outIdx + 1] = (output.toInt() shr 8).toByte()
        }

        return outputData
    }

    /**
     * Reset delay buffers
     */
    fun reset() {
        delayBuffers.forEach { it.clear() }
    }
}
